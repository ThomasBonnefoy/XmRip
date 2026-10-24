using System.Globalization;
using XmRipPc.Bluetooth;
using XmRipPc.Mdr;

namespace XmRipPc;

/// <summary>Résultat d'une session : seuls les champs lus ou modifiés sont renseignés.</summary>
internal sealed record Snapshot
{
    public BatteryReport? Battery { get; init; }
    public NcAsmState? Nc { get; init; }
    public int? Volume { get; init; }
    public EqState? Eq { get; init; }
    public SettingsState? Settings { get; init; }
    public PairedDevicesInfo? Devices { get; init; }
    public string? DevicesError { get; init; }
    public string? Message { get; init; }
}

/// <summary>Déclenche une action une fois que la valeur est restée stable (curseurs).</summary>
internal sealed class Debouncer
{
    private readonly System.Windows.Forms.Timer _timer;

    public Debouncer(int ms, Action action)
    {
        _timer = new System.Windows.Forms.Timer { Interval = ms };
        _timer.Tick += (_, _) => { _timer.Stop(); action(); };
    }

    /// <summary>Horodatage du dernier geste sur un curseur : l'état poussé par le casque ne l'écrase pas aussitôt.</summary>
    public static long LastTick { get; private set; }

    public void Trigger()
    {
        LastTick = Environment.TickCount64;
        _timer.Stop();
        _timer.Start();
    }
}

/// <summary>Modèle et firmware du casque, mémorisés pour ne pas les redemander à chaque connexion (2 échanges).</summary>
internal static class DeviceMemory
{
    private static readonly string File_ =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "XmRipPc", "info.txt");

    public static (string? Model, string? Firmware) Load()
    {
        try
        {
            var lines = File.ReadAllLines(File_);
            return (lines.ElementAtOrDefault(0) is { Length: > 0 } m ? m : null, lines.ElementAtOrDefault(1) is { Length: > 0 } f ? f : null);
        }
        catch { return (null, null); }
    }

    public static void Save(string? model, string? firmware)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(File_)!);
            File.WriteAllLines(File_, new[] { model ?? "", firmware ?? "" });
        }
        catch { /* mémoire facultative */ }
    }
}

/// <summary>Journal sur disque (%LOCALAPPDATA%\XmRipPc\journal.txt) : garde les trames et les coupures de liaison, pour les relire après coup.</summary>
internal static class FileLog
{
    private static readonly string Path_ =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "XmRipPc", "journal.txt");
    private static readonly object Lock = new();

    public static string FilePath => Path_;

    public static void Write(string text)
    {
        try
        {
            lock (Lock)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path_)!);
                var info = new FileInfo(Path_);
                if (info.Exists && info.Length > 2_000_000) File.Move(Path_, Path_ + ".old", true);
                File.AppendAllText(Path_, $"{DateTime.Now:HH:mm:ss.fff}  {text}{Environment.NewLine}");
            }
        }
        catch { /* journal facultatif */ }
    }
}

internal sealed class MainForm : Form
{
    private static readonly string[] BandNames = { "31 Hz", "63 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz" };

    private readonly HeadsetLink _link = new();
    private readonly SemaphoreSlim _gate = new(1, 1);
    private volatile bool _showFrames;
    private bool _updating;
    private int _pending;

    private MdrSession? _session;
    private long _lastActivity;
    private BatteryReport? _shownReport;
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 300 };
    private readonly Label _conn = new();
    private readonly TabControl _tabs = new() { Dock = DockStyle.Fill, Padding = new Point(14, 6) };

    private readonly Label _title = new(), _battery = new(), _status = new();
    private readonly Button _refresh = new();

    private readonly RadioButton _rbNc = new(), _rbAmbient = new(), _rbOff = new();
    private readonly TrackBar _ambient = new(), _volume = new();
    private readonly Label _ambientValue = new(), _volumeValue = new();
    private readonly CheckBox _auto = new();

    private readonly Label _eqState = new();
    private readonly TrackBar[] _bands = new TrackBar[10];
    private readonly Label[] _bandGain = new Label[10];

    private readonly CheckBox _dsee = new(), _chat = new(), _wear = new();
    private readonly RadioButton _lmStd = new(), _lmBgm = new(), _lmCinema = new();
    private readonly RadioButton _ncbAll = new(), _ncbNcAsm = new(), _ncbNcOff = new(), _ncbAsmOff = new();

    private readonly ComboBox _actDouble = new(), _actTriple = new();
    private readonly TextBox _targetDouble = new(), _targetTriple = new();
    private readonly Label _lastGestureLabel = new();

    private readonly ListView _devices = new();
    private readonly Label _pairing = new();

    private readonly TextBox _log = new();
    private readonly CheckBox _frames = new();

    private readonly Debouncer _ambientSend, _volumeSend, _eqSend;

    public MainForm()
    {
        Text = "XmRip PC";
        Font = new Font("Segoe UI", 10f);
        MinimumSize = new Size(640, 640);
        ClientSize = new Size(700, 760);
        StartPosition = FormStartPosition.CenterScreen;

        GestureSettings.Load();
        _ambientSend = new Debouncer(500, () => _ = RunAsync("Niveau ambiant", (s, _) => new Snapshot { Nc = s.SetAmbientLevel(_ambient.Value, _auto.Checked) }));
        _volumeSend = new Debouncer(400, () => _ = RunAsync("Volume", (s, _) => new Snapshot { Volume = s.SetMusicVolume(_volume.Value) }));
        _eqSend = new Debouncer(600, () => _ = RunAsync("Courbe de l'égaliseur", (s, _) =>
            new Snapshot { Eq = s.SetEqBands(_bands.Select(b => b.Value).ToList()) }));

        _tabs.TabPages.Add(BuildSoundTab());
        _tabs.TabPages.Add(BuildEqTab());
        _tabs.TabPages.Add(BuildSettingsTab());
        _tabs.TabPages.Add(BuildDevicesTab());
        _tabs.TabPages.Add(BuildGesturesTab());
        _tabs.SelectedIndexChanged += (_, _) => LoadCurrentTab();

        Controls.Add(_tabs);
        Controls.Add(BuildLogPanel());
        Controls.Add(BuildHeader());

        _tick.Tick += OnTick;
        _tick.Start();
        FormClosing += (_, _) => { _tick.Stop(); DropSession(); };
        Load += (_, _) => _ = RefreshAllAsync();
    }

    // ============================================================ construction de l'interface

    private Control BuildHeader()
    {
        var panel = new Panel { Dock = DockStyle.Top, Height = 78, Padding = new Padding(16, 10, 16, 6) };
        _title.Text = "Casque Sony";
        _title.Font = new Font(Font.FontFamily, 14f, FontStyle.Bold);
        _title.AutoSize = true;
        _title.Location = new Point(16, 10);
        _battery.Text = "Non connecté";
        _battery.AutoSize = true;
        _battery.Location = new Point(18, 40);
        _status.AutoSize = true;
        _status.Location = new Point(18, 58);
        _status.ForeColor = SystemColors.GrayText;
        _conn.Text = "○ Non connecté";
        _conn.ForeColor = SystemColors.GrayText;
        _conn.AutoSize = true;
        _conn.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        _conn.Location = new Point(panel.Width - 330, 24);
        panel.Controls.Add(_conn);
        _refresh.Text = "Actualiser";
        _refresh.Size = new Size(110, 34);
        _refresh.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        _refresh.Location = new Point(panel.Width - 130, 14);
        _refresh.Click += (_, _) => _ = RefreshAllAsync(full: true);
        panel.Controls.AddRange(new Control[] { _title, _battery, _status, _refresh });
        return panel;
    }

    private Control BuildLogPanel()
    {
        var panel = new Panel { Dock = DockStyle.Bottom, Height = 150, Padding = new Padding(10) };
        _log.Multiline = true;
        _log.ReadOnly = true;
        _log.ScrollBars = ScrollBars.Vertical;
        _log.Dock = DockStyle.Fill;
        _log.Font = new Font("Consolas", 9f);
        _frames.Text = "Afficher les trames";
        _frames.AutoSize = true;
        _frames.Dock = DockStyle.Top;
        _frames.CheckedChanged += (_, _) => _showFrames = _frames.Checked;
        panel.Controls.Add(_log);
        panel.Controls.Add(_frames);
        return panel;
    }

    private static GroupBox Group(string title, int height)
    {
        return new GroupBox { Text = title, Width = 620, Height = height, Margin = new Padding(0, 0, 0, 12), Padding = new Padding(12, 8, 12, 8) };
    }

    private TabPage BuildSoundTab()
    {
        var page = new TabPage("Son") { Padding = new Padding(16), AutoScroll = true };
        var flow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true };

        var nc = Group("Contrôle du bruit", 190);
        _rbNc.Text = "Réduction de bruit";
        _rbAmbient.Text = "Son ambiant";
        _rbOff.Text = "Désactivé";
        int x = 16;
        foreach (var rb in new[] { _rbNc, _rbAmbient, _rbOff })
        {
            rb.AutoSize = true;
            rb.Location = new Point(x, 30);
            x += 180;
            nc.Controls.Add(rb);
        }
        var lvl = new Label { Text = "Niveau ambiant", AutoSize = true, Location = new Point(16, 76) };
        _ambient.Minimum = 1; _ambient.Maximum = C.AmbientLevelMax; _ambient.Value = C.AmbientLevelMax;
        _ambient.TickFrequency = 1; _ambient.Width = 330; _ambient.Location = new Point(150, 68);
        _ambientValue.AutoSize = true; _ambientValue.Location = new Point(496, 76); _ambientValue.Text = "20";
        _auto.Text = "Automatique"; _auto.AutoSize = true; _auto.Location = new Point(16, 126);
        nc.Controls.AddRange(new Control[] { lvl, _ambient, _ambientValue, _auto });

        void SetMode(int mode) => _ = RunAsync("Mode de bruit", (s, _) =>
            new Snapshot { Nc = s.SetNcAsm(mode, _ambient.Value, _auto.Checked) });
        _rbNc.CheckedChanged += (_, _) => { if (!_updating && _rbNc.Checked) SetMode(0); };
        _rbAmbient.CheckedChanged += (_, _) => { if (!_updating && _rbAmbient.Checked) SetMode(1); };
        _rbOff.CheckedChanged += (_, _) => { if (!_updating && _rbOff.Checked) SetMode(2); };
        _ambient.ValueChanged += (_, _) => { _ambientValue.Text = _ambient.Value.ToString(); if (!_updating) _ambientSend.Trigger(); };
        _auto.CheckedChanged += (_, _) => { if (!_updating) _ambientSend.Trigger(); };

        var vol = Group("Volume", 100);
        _volume.Minimum = 0; _volume.Maximum = C.PlayVolumeMax; _volume.TickFrequency = 5;
        _volume.Width = 480; _volume.Location = new Point(16, 34);
        _volumeValue.AutoSize = true; _volumeValue.Location = new Point(510, 40); _volumeValue.Text = "0";
        vol.Controls.AddRange(new Control[] { _volume, _volumeValue });
        _volume.ValueChanged += (_, _) => { _volumeValue.Text = _volume.Value.ToString(); if (!_updating) _volumeSend.Trigger(); };

        var play = Group("Lecture", 80);
        var buttons = new (string Text, int Code)[]
        {
            ("◀◀ Précédent", C.PlayCtrlTrackDown), ("▶ Lecture", C.PlayCtrlPlay),
            ("❚❚ Pause", C.PlayCtrlPause), ("Suivant ▶▶", C.PlayCtrlTrackUp),
        };
        int bx = 16;
        foreach (var (text, code) in buttons)
        {
            var b = new Button { Text = text, Size = new Size(140, 34), Location = new Point(bx, 28) };
            bx += 148;
            b.Click += (_, _) => _ = RunAsync(text, (s, _) => { s.PlayControl(code); return null; });
            play.Controls.Add(b);
        }

        flow.Controls.AddRange(new Control[] { nc, vol, play });
        page.Controls.Add(flow);
        return page;
    }

    private TabPage BuildEqTab()
    {
        var page = new TabPage("Égaliseur") { Padding = new Padding(16) };

        var top = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 92, FlowDirection = FlowDirection.LeftToRight, WrapContents = true };
        var presets = new (string Text, int Id)[]
        {
            ("Désactivé", C.EqOff), ("Heavy", C.EqHeavy), ("Clear", C.EqClear),
            ("Hard", C.EqHard), ("Soft", C.EqSoft), ("Perso", C.EqCustom),
        };
        foreach (var (text, id) in presets)
        {
            var b = new Button { Text = text, Size = new Size(96, 34) };
            b.Click += (_, _) => _ = RunAsync($"Préréglage {text}", (s, _) => new Snapshot { Eq = s.SetEqPreset(id) });
            top.Controls.Add(b);
        }
        _eqState.AutoSize = true;
        _eqState.Margin = new Padding(8, 12, 0, 0);
        _eqState.Text = "État : non lu";
        top.Controls.Add(_eqState);

        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 10, RowCount = 3 };
        for (int i = 0; i < 10; i++) grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 10f));
        grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
        for (int i = 0; i < 10; i++)
        {
            int index = i;
            // Curseur vertical : le minimum est en bas, donc la valeur du TrackBar est le gain tel quel.
            var tb = new TrackBar
            {
                Orientation = Orientation.Vertical, Minimum = C.EqGainMin, Maximum = C.EqGainMax,
                TickFrequency = 1, Dock = DockStyle.Fill, Value = 0,
            };
            var gain = new Label { Text = "0", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter };
            var name = new Label { Text = BandNames[i], Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, Font = new Font(Font.FontFamily, 8f) };
            tb.ValueChanged += (_, _) =>
            {
                _bandGain[index].Text = FormatGain(tb.Value);
                if (!_updating) _eqSend.Trigger();
            };
            _bands[i] = tb;
            _bandGain[i] = gain;
            grid.Controls.Add(gain, i, 0);
            grid.Controls.Add(tb, i, 1);
            grid.Controls.Add(name, i, 2);
        }

        page.Controls.Add(grid);
        page.Controls.Add(top);
        return page;
    }

    private static string FormatGain(int gain) => gain > 0 ? $"+{gain}" : gain.ToString();

    private TabPage BuildSettingsTab()
    {
        var page = new TabPage("Réglages") { Padding = new Padding(16) };
        var flow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true };

        var general = Group("Casque", 150);
        _dsee.Text = "DSEE (amélioration du son)";
        _chat.Text = "Speak-to-Chat";
        _wear.Text = "Mettre en pause quand j'ôte le casque";
        int y = 30;
        foreach (var cb in new[] { _dsee, _chat, _wear })
        {
            cb.AutoSize = true; cb.Location = new Point(16, y); cb.Enabled = false;
            y += 36;
            general.Controls.Add(cb);
        }
        _dsee.CheckedChanged += (_, _) => { if (!_updating) _ = WriteSetting("DSEE", s => s.SetDsee(_dsee.Checked)); };
        _chat.CheckedChanged += (_, _) => { if (!_updating) _ = WriteSetting("Speak-to-Chat", s => s.SetSpeakToChat(_chat.Checked)); };
        _wear.CheckedChanged += (_, _) => { if (!_updating) _ = WriteSetting("Pause au retrait", s => s.SetPauseWhenRemoved(_wear.Checked)); };

        var listening = Group("Mode d'écoute", 80);
        _lmStd.Text = "Standard"; _lmBgm.Text = "Musique de fond"; _lmCinema.Text = "Cinéma";
        int x = 16;
        foreach (var rb in new[] { _lmStd, _lmBgm, _lmCinema })
        {
            rb.AutoSize = true; rb.Location = new Point(x, 32); rb.Enabled = false;
            x += 190;
            listening.Controls.Add(rb);
        }
        void SetLm(ListeningMode m, string label) => _ = WriteSetting($"Mode d'écoute : {label}", s => s.SetListeningMode(m));
        _lmStd.CheckedChanged += (_, _) => { if (!_updating && _lmStd.Checked) SetLm(ListeningMode.Standard, "Standard"); };
        _lmBgm.CheckedChanged += (_, _) => { if (!_updating && _lmBgm.Checked) SetLm(ListeningMode.Bgm, "Musique de fond"); };
        _lmCinema.CheckedChanged += (_, _) => { if (!_updating && _lmCinema.Checked) SetLm(ListeningMode.Cinema, "Cinéma"); };

        var power = Group("Alimentation", 80);
        var off = new Button { Text = "Éteindre le casque", Size = new Size(200, 34), Location = new Point(16, 28) };
        off.Click += (_, _) =>
        {
            if (MessageBox.Show(this, "Éteindre le casque ?", "XmRip PC", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            _ = RunAsync("Extinction du casque", (s, _) => { s.PowerOff(); return new Snapshot { Message = "Casque éteint." }; });
        };
        power.Controls.Add(off);

        var button = Group("Bouton de bruit (oreillette gauche)", 170);
        var choices = new (RadioButton Radio, string Text, int Function)[]
        {
            (_ncbAll, "Les trois : bruit, transparent, normal", C.NcButtonAll),
            (_ncbNcAsm, "Bruit et transparent seulement", C.NcButtonNcAsm),
            (_ncbNcOff, "Bruit et normal seulement (non essayé)", C.NcButtonNcOff),
            (_ncbAsmOff, "Transparent et normal seulement (non essayé)", C.NcButtonAsmOff),
        };
        int by = 30;
        foreach (var (radio, text, function) in choices)
        {
            radio.Text = text; radio.AutoSize = true; radio.Location = new Point(16, by); radio.Enabled = false;
            by += 32;
            var fn = function;
            radio.CheckedChanged += (_, _) => { if (!_updating && radio.Checked) _ = WriteSetting($"Bouton de bruit : {text}", s => s.SetNcButton(fn)); };
            button.Controls.Add(radio);
        }

        flow.Controls.AddRange(new Control[] { general, listening, button, BuildGestureActionsGroup(), power });
        page.Controls.Add(flow);
        return page;
    }

    private static readonly string[] ActionLabels =
    {
        "Aucune", "Lecture / pause (touche média Windows)", "Piste suivante (touche média)",
        "Piste précédente (touche média)", "Lancer un programme ou ouvrir un lien",
    };

    /// <summary>Actions du PC déclenchées par le double et le triple appui du bouton de bruit.</summary>
    private GroupBox BuildGestureActionsGroup()
    {
        var group = Group("Actions sur le PC (bouton de bruit)", 252);
        var hint = new Label
        {
            Text = "Le casque annonce les double et triple appuis du bouton de bruit. Choisis ce que le PC fait.\n" +
                   "Une action choisie garde la liaison ouverte : le téléphone ne peut plus lire le casque en même temps.",
            AutoSize = false, Location = new Point(16, 24), Size = new Size(590, 40), ForeColor = SystemColors.GrayText,
        };
        group.Controls.Add(hint);
        int y = 72;
        void Row(string title, string gesture, ComboBox combo, TextBox target)
        {
            group.Controls.Add(new Label { Text = title, AutoSize = true, Location = new Point(16, y + 4) });
            combo.DropDownStyle = ComboBoxStyle.DropDownList;
            combo.Items.AddRange(ActionLabels);
            combo.Location = new Point(130, y);
            combo.Width = 330;
            var current = GestureSettings.Get(gesture);
            combo.SelectedIndex = (int)current.Action;
            target.Location = new Point(130, y + 34);
            target.Width = 476;
            target.Text = current.Target;
            target.Enabled = current.Action == GestureAction.Launch;
            void Save()
            {
                var action = (GestureAction)Math.Max(combo.SelectedIndex, 0);
                target.Enabled = action == GestureAction.Launch;
                GestureSettings.Set(gesture, new GestureBinding(action, target.Text));
                // La liaison doit rester ouverte pour entendre les gestes : on la (re)prend tout de suite.
                if (GestureSettings.Any && _session == null) _nextReconnect = 0;
            }
            combo.SelectedIndexChanged += (_, _) => Save();
            target.Leave += (_, _) => Save();
            group.Controls.AddRange(new Control[] { combo, target });
            y += 72;
        }
        Row("Double appui", GestureSettings.DoubleButton, _actDouble, _targetDouble);
        Row("Triple appui", GestureSettings.TripleButton, _actTriple, _targetTriple);
        _lastGestureLabel.AutoSize = true;
        _lastGestureLabel.Location = new Point(16, y - 4);
        _lastGestureLabel.ForeColor = SystemColors.GrayText;
        _lastGestureLabel.Text = "Dernier geste reçu : aucun";
        group.Controls.Add(_lastGestureLabel);
        return group;
    }

    /// <summary>Geste annoncé par le casque (fil de lecture de la session) : l'affiche et lance l'action choisie.</summary>
    private void OnGestureEvent(string gesture)
    {
        FileLog.Write($"Geste reçu : {gesture}");
        if (IsDisposed || !IsHandleCreated) return;
        BeginInvoke(() =>
        {
            _lastGestureLabel.Text = $"Dernier geste reçu : {GestureSettings.Describe(gesture)} ({DateTime.Now:HH:mm:ss})";
            var binding = GestureSettings.Get(gesture);
            if (binding.Action == GestureAction.None) return;
            var error = GestureSettings.Run(binding);
            Log(error == null ? $"Geste « {GestureSettings.Describe(gesture)} » : action lancée" : $"Geste « {GestureSettings.Describe(gesture)} » : {error}");
        });
    }

    private Task WriteSetting(string label, Func<MdrSession, SettingsState> write) =>
        RunAsync(label, (s, _) => new Snapshot { Settings = write(s) });

    // ============================================================ test des gestes du casque

    private readonly GestureCollector _gesture = new();
    private readonly List<Button> _gestureButtons = new();
    private TextBox _gestureResult = null!;
    private bool _gestureBusy;
    private volatile bool _gestureLinkLost;
    private readonly List<string> _gestureHistory = new();

    private TabPage BuildGesturesTab()
    {
        var page = new TabPage("Gestes") { Padding = new Padding(16) };

        var intro = new Label
        {
            Text = "Appuie sur le bouton du geste que tu vas faire, puis fais-le sur le casque dans les 8 secondes.\n" +
                   "L'appli note ce que le casque lui envoie pendant ce temps.",
            Dock = DockStyle.Top,
            Height = 52,
        };

        var buttons = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 150, FlowDirection = FlowDirection.LeftToRight, WrapContents = true };
        foreach (var name in new[] { "1 tap", "2 taps", "3 taps", "Maintien long", "Glisser vers le haut", "Glisser vers le bas", "Glisser vers la droite", "Glisser vers la gauche", "Je ne fais rien (témoin)" })
        {
            var b = new Button { Text = name, Size = new Size(190, 34) };
            var gesture = name;
            b.Click += async (_, _) => await StartGestureAsync(gesture);
            _gestureButtons.Add(b);
            buttons.Controls.Add(b);
        }

        var copy = new Button { Text = "Copier le résultat", Size = new Size(190, 34) };
        copy.Click += (_, _) =>
        {
            if (_gestureResult.TextLength > 0) Clipboard.SetText(_gestureResult.Text);
        };
        buttons.Controls.Add(copy);

        _gestureResult = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill, Font = new Font("Consolas", 9.5f),
        };

        page.Controls.Add(_gestureResult);
        page.Controls.Add(buttons);
        page.Controls.Add(intro);
        return page;
    }

    private async Task StartGestureAsync(string name)
    {
        if (_gestureBusy) return;
        _gestureBusy = true;
        foreach (var b in _gestureButtons) b.Enabled = false;
        _gestureResult.Text = "Connexion au casque…";
        try
        {
            await RunAsync("Connexion", (_, _) => null);
            if (_session == null)
            {
                _gestureResult.Text = "Connexion au casque impossible.";
                return;
            }
            _gestureLinkLost = false;
            _lastActivity = Environment.TickCount64;
            _gesture.Begin(name);
            _gestureResult.Text = $"Fais le geste « {name} » MAINTENANT (8 secondes)…";
            await Task.Delay(8_000);
            _gestureHistory.Insert(0, _gesture.Finish(_gestureLinkLost));
            if (_gestureHistory.Count > 8) _gestureHistory.RemoveAt(_gestureHistory.Count - 1);
            _gestureResult.Text = string.Join(Environment.NewLine + Environment.NewLine, _gestureHistory);
            Log(_gestureHistory[0].Replace(Environment.NewLine, " | "));
        }
        finally
        {
            foreach (var b in _gestureButtons) b.Enabled = true;
            _gestureBusy = false;
        }
    }

    private TabPage BuildDevicesTab()
    {
        var page = new TabPage("Appareils") { Padding = new Padding(16) };

        _devices.View = View.Details;
        _devices.FullRowSelect = true;
        _devices.HideSelection = false;
        _devices.MultiSelect = false;
        _devices.Dock = DockStyle.Fill;
        _devices.Columns.Add("Appareil", 260);
        _devices.Columns.Add("Adresse", 160);
        _devices.Columns.Add("État", 140);

        var bar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 52, FlowDirection = FlowDirection.LeftToRight };
        Button Btn(string text, Func<string, string> labelOf, Func<MdrSession, string, int> action)
        {
            var b = new Button { Text = text, Size = new Size(150, 34) };
            b.Click += (_, _) =>
            {
                if (_devices.SelectedItems.Count == 0) { SetStatus("Sélectionne un appareil.", true); return; }
                var address = (string)_devices.SelectedItems[0].Tag!;
                var name = _devices.SelectedItems[0].Text;
                _ = RunAsync($"{labelOf(name)}", (s, _) =>
                {
                    int code = action(s, address);
                    return new Snapshot { Devices = s.ReadPairedDevices(), Message = $"{labelOf(name)} : code 0x{code:X2}" };
                });
            };
            return b;
        }
        bar.Controls.Add(Btn("Connecter", n => $"Connexion de {n}", (s, a) => s.ConnectPairedDevice(a)));
        bar.Controls.Add(Btn("Déconnecter", n => $"Déconnexion de {n}", (s, a) => s.DisconnectPairedDevice(a)));
        bar.Controls.Add(Btn("Lire ici", n => $"Lecture sur {n}", (s, a) => s.SwitchSource(a)));

        _pairing.Dock = DockStyle.Bottom;
        _pairing.Height = 28;
        _pairing.Text = "Mode d'appairage : non lu";

        page.Controls.Add(_devices);
        page.Controls.Add(bar);
        page.Controls.Add(_pairing);
        return page;
    }

    // ============================================================ sessions

    private readonly bool[] _tabLoaded = new bool[5];

    /// <summary>
    /// Affiche l'essentiel dès la connexion. Égaliseur, réglages et appareils ne sont lus qu'à l'ouverture de
    /// leur onglet : chaque lecture coûte 0,4 à 0,9 s de liaison, et celles faites pour rien retardent l'action
    /// suivante de l'utilisateur. [full] (bouton Actualiser) relit aussi firmware et modèle.
    /// </summary>
    private async Task RefreshAllAsync(bool full = false)
    {
        Array.Clear(_tabLoaded);
        await RunAsync("Connexion", (_, _) => null);
        if (_session == null) return;
        if (full)
        {
            await RunAsync("Informations du casque", (s, _) =>
            {
                var (model, firmware) = s.RefreshDeviceInfo();
                DeviceMemory.Save(model, firmware);
                return null;
            });
        }
        LoadCurrentTab();
    }

    private void LoadCurrentTab()
    {
        int index = _tabs.SelectedIndex;
        if (index < 1 || index > 3 || _tabLoaded[index] || _session == null) return;
        _tabLoaded[index] = true;
        _ = RunAsync(_tabs.SelectedTab!.Text switch { "Égaliseur" => "Lecture de l'égaliseur", "Réglages" => "Lecture des réglages", _ => "Lecture des appareils" },
            (s, _) => index switch
            {
                1 => new Snapshot { Eq = s.ReadEq() },
                2 => new Snapshot { Settings = s.ReadSettings() },
                _ => ReadDevices(s),
            });
    }

    private static Snapshot ReadDevices(MdrSession s)
    {
        try { return new Snapshot { Devices = s.ReadPairedDevices() }; }
        catch (MdrException e) { return new Snapshot { DevicesError = e.Message }; }
    }

    private void Trace(string line)
    {
        if (_showFrames) Log(line);
        else FileLog.Write(line);
    }

    /// <summary>
    /// Exécute [work] sur la session ouverte avec le casque (la connexion et le handshake ne se paient qu'une fois,
    /// pas à chaque action). Les actions sont mises en file d'attente. Si la session gardée est périmée (casque
    /// éteint ou éloigné entre-temps), l'action est rejouée une fois sur une connexion neuve.
    /// </summary>
    private async Task RunAsync(string label, Func<MdrSession, BatteryReport, Snapshot?> work)
    {
        Interlocked.Increment(ref _pending);
        await _gate.WaitAsync();
        SetStatus($"{label}…", false);
        var clock = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            var snap = await Task.Run(async () =>
            {
                bool reused = _session != null;
                var session = _session ?? await ConnectNewAsync();
                Snapshot? changed;
                try
                {
                    changed = work(session, session.Report!);
                }
                catch (Exception e) when (reused)
                {
                    Log($"Session périmée ({e.Message}), reconnexion…");
                    DropSession();
                    session = await ConnectNewAsync();
                    changed = work(session, session.Report!);
                }
                catch
                {
                    DropSession();
                    throw;
                }
                _lastActivity = Environment.TickCount64;
                var report = session.Report!;
                return (changed ?? new Snapshot()) with
                {
                    Battery = report,
                    Nc = changed?.Nc ?? report.NcAsm,
                    Volume = changed?.Volume ?? report.Volume,
                };
            });
            // Si d'autres actions attendent, cet état est déjà périmé : il écraserait le choix de l'utilisateur.
            if (Volatile.Read(ref _pending) <= 1) Apply(snap);
            var done = $"{snap.Message ?? $"{label} : fait"} ({clock.ElapsedMilliseconds} ms)";
            SetStatus(done, false);
            Log(done);
        }
        catch (Exception e)
        {
            SetStatus($"{label} : {e.Message}", true);
            Log($"{label} : {e.GetType().Name} : {e.Message}");
            SetConnected(false, "Non connecté");
        }
        finally
        {
            Interlocked.Decrement(ref _pending);
            _gate.Release();
        }
    }

    // ============================================================ session gardée ouverte

    private const int IdleCloseMs = 120_000;
    private long _nextReconnect;
    private int _reconnectDelay = 8_000;

    /// <summary>Ouvre la connexion et fait le handshake (deux essais). Appelé sous <c>_gate</c>, hors thread UI.</summary>
    private async Task<MdrSession> ConnectNewAsync()
    {
        for (int attempt = 1; ; attempt++)
        {
            MdrSession? session = null;
            try
            {
                var clock = System.Diagnostics.Stopwatch.StartNew();
                session = await _link.OpenAsync(Trace);
                long opened = clock.ElapsedMilliseconds;
                var (knownModel, knownFirmware) = DeviceMemory.Load();
                session.FrameReceived += _gesture.OnFrame;
                session.GestureReceived += OnGestureEvent;
                session.SeedDeviceInfo(knownModel, knownFirmware);
                var first = session.HandshakeAndBattery();
                if (first.Model != knownModel || first.Firmware != knownFirmware) DeviceMemory.Save(first.Model, first.Firmware);
                Log($"Connexion {opened} ms, handshake {clock.ElapsedMilliseconds - opened} ms");
                _session = session;
                _lastActivity = Environment.TickCount64;
                _reconnectDelay = 8_000;
                SetConnected(true, "Connecté");
                return session;
            }
            catch (Exception e)
            {
                session?.Dispose();
                if (attempt >= 2) throw;
                Log($"Connexion échouée ({e.Message}), nouvel essai…");
                await Task.Delay(1000);
            }
        }
    }

    private void DropSession()
    {
        var s = _session;
        _session = null;
        try { s?.Dispose(); } catch { }
    }

    /// <summary>
    /// Toutes les 300 ms, quand rien d'autre ne tourne : acquitte les trames du casque et affiche ses changements
    /// (batterie, bruit et volume modifiés depuis le casque ou le téléphone). Ferme la session après 2 min d'inactivité
    /// pour laisser le casque libre pour un autre appareil.
    /// </summary>
    private async void OnTick(object? sender, EventArgs e)
    {
        // Une action de geste est choisie mais la liaison est perdue : on la reprend, de plus en plus rarement si
        // le casque reste injoignable (éteint, éloigné).
        if (_session == null && GestureSettings.Any && _pending == 0 && Environment.TickCount64 >= _nextReconnect)
        {
            _nextReconnect = Environment.TickCount64 + _reconnectDelay;
            _reconnectDelay = Math.Min(_reconnectDelay * 2, 60_000);
            _ = RunAsync("Reconnexion (gestes)", (_, _) => null);
            return;
        }
        if (_session == null || !_gate.Wait(0)) return;
        BatteryReport? report = null;
        PairedDevicesInfo? devices = null;
        int devicesStamp = 0;
        string? closed = null;
        try
        {
            if (!GestureSettings.Any && Environment.TickCount64 - _lastActivity > IdleCloseMs)
            {
                DropSession();
                closed = "Inactif : reconnexion à la prochaine action";
            }
            else
            {
                var session = _session;
                await Task.Run(session.Drain);
                report = session.Report;
                devices = session.LatestDevices;
                devicesStamp = session.DevicesStamp;
            }
        }
        catch (Exception ex)
        {
            DropSession();
            if (_gesture.Active) _gestureLinkLost = true;
            closed = $"Liaison coupée ({ex.Message})";
        }
        finally
        {
            _gate.Release();
        }

        if (closed != null)
        {
            SetConnected(false, closed);
            Log(closed);
            // Pendant un test de geste, on reprend la session tout de suite pour ne pas perdre le reste de la fenêtre.
            if (_gesture.Active) _ = RunAsync("Reconnexion pendant le test", (_, _) => null);
            return;
        }
        // Pas d'affichage pendant qu'une action est en cours ou juste après un geste sur un curseur.
        if (report != null && report != _shownReport && _pending == 0 && Environment.TickCount64 - Debouncer.LastTick > 1500)
            Apply(new Snapshot { Battery = report, Nc = report.NcAsm, Volume = report.Volume });
        // Appareil qui se connecte ou se déconnecte du casque : la liste suit toute seule (le casque l'annonce).
        if (devices != null && devicesStamp != _shownDevicesStamp && _tabLoaded[3] && _pending == 0)
        {
            _shownDevicesStamp = devicesStamp;
            Apply(new Snapshot { Devices = devices });
        }
    }

    private int _shownDevicesStamp;

    private void SetConnected(bool connected, string text)
    {
        if (InvokeRequired) { BeginInvoke(() => SetConnected(connected, text)); return; }
        _conn.Text = (connected ? "● " : "○ ") + text;
        _conn.ForeColor = connected ? Color.SeaGreen : SystemColors.GrayText;
    }

    // ============================================================ affichage de l'état

    private void Apply(Snapshot s)
    {
        _updating = true;
        try
        {
            if (s.Battery is { } b)
            {
                _shownReport = b;
                _title.Text = _link.DeviceName ?? b.Model ?? "Casque Sony";
                _battery.Text = $"{b.Model}  •  batterie {b.Level} % ({b.ChargingLabel})  •  firmware {b.Firmware}";
            }
            if (s.Nc is { } nc)
            {
                (!nc.Enabled ? _rbOff : nc.Mode == C.NcAsmModeAsm ? _rbAmbient : _rbNc).Checked = true;
                if (nc.Mode == C.NcAsmModeAsm) _ambient.Value = Math.Clamp(nc.AmbientLevel, 1, C.AmbientLevelMax);
                _auto.Checked = nc.Auto;
            }
            if (s.Volume is int v) _volume.Value = Math.Clamp(v, 0, C.PlayVolumeMax);
            if (s.Eq is { } eq)
            {
                _eqState.Text = $"État : {eq.Label}";
                for (int i = 0; i < eq.Bands.Count && i < _bands.Length; i++)
                    _bands[i].Value = Math.Clamp(eq.Bands[i], C.EqGainMin, C.EqGainMax);
            }
            if (s.Settings is { } st) ApplySettings(st);
            if (s.Devices is { } d) ApplyDevices(d);
            else if (s.DevicesError != null) _pairing.Text = s.DevicesError;
        }
        finally
        {
            _updating = false;
        }
    }

    private void ApplySettings(SettingsState st)
    {
        void Set(CheckBox cb, bool? value)
        {
            cb.Enabled = value != null;
            cb.Checked = value == true;
        }
        Set(_dsee, st.Dsee);
        Set(_chat, st.SpeakToChat);
        Set(_wear, st.PauseWhenRemoved);
        bool known = st.Listening != null;
        foreach (var rb in new[] { _lmStd, _lmBgm, _lmCinema }) rb.Enabled = known;
        (st.Listening switch { ListeningMode.Bgm => _lmBgm, ListeningMode.Cinema => _lmCinema, _ => _lmStd }).Checked = known;
        var ncb = new[] { (_ncbAll, C.NcButtonAll), (_ncbNcAsm, C.NcButtonNcAsm), (_ncbNcOff, C.NcButtonNcOff), (_ncbAsmOff, C.NcButtonAsmOff) };
        foreach (var (radio, function) in ncb)
        {
            radio.Enabled = st.NcButton != null;
            radio.Checked = st.NcButton == function;
        }
    }

    private void ApplyDevices(PairedDevicesInfo info)
    {
        _devices.Items.Clear();
        foreach (var d in info.Devices)
        {
            var state = d.IsPlayback ? "connecté (lecture)" : d.Connected ? "connecté" : "—";
            var item = new ListViewItem(new[] { d.Name, d.Address, state }) { Tag = d.Address };
            _devices.Items.Add(item);
        }
        _pairing.Text = info.PairingMode switch
        {
            true => "Mode d'appairage : actif",
            false => "Mode d'appairage : inactif",
            _ => "Mode d'appairage : inconnu",
        };
    }

    private void SetStatus(string text, bool error)
    {
        if (InvokeRequired) { BeginInvoke(() => SetStatus(text, error)); return; }
        _status.Text = text;
        _status.ForeColor = error ? Color.Firebrick : SystemColors.GrayText;
    }

    private void Log(string text)
    {
        FileLog.Write(text);
        if (InvokeRequired) { BeginInvoke(() => Log(text)); return; }
        _log.AppendText($"{DateTime.Now.ToString("HH:mm:ss", CultureInfo.InvariantCulture)}  {text}{Environment.NewLine}");
    }
}
