using System.Collections.Concurrent;
using System.Text;

namespace XmRipPc.Mdr;

internal sealed class MdrException : IOException
{
    public MdrException(string message) : base(message) { }
}

internal sealed record BatteryReport(string? Model, string? Firmware, int Level, int Charging, NcAsmState? NcAsm, int? Volume)
{
    public string ChargingLabel => Charging switch { 0 => "pas en charge", 1 => "en charge", 3 => "chargée", _ => "inconnu" };
}

internal sealed record NcAsmState(bool Enabled, int Mode, int AmbientLevel, bool Auto, int Sensitivity);

internal sealed record EqState(int Preset, IReadOnlyList<int> Bands)
{
    public string Label => Preset switch
    {
        C.EqOff => "Désactivé",
        C.EqHeavy => "Heavy",
        C.EqClear => "Clear",
        C.EqHard => "Hard",
        C.EqSoft => "Soft",
        C.EqCustom => "Personnalisé",
        _ => $"Préréglage 0x{Preset:X2}",
    };
}

internal enum ListeningMode { Standard, Bgm, Cinema }

internal sealed record SettingsState(bool? PauseWhenRemoved, bool? SpeakToChat, bool? Dsee, bool? Bgm, int? BgmRoom, bool? Cinema, int? NcButton = null)
{
    public ListeningMode? Listening =>
        Bgm == true ? ListeningMode.Bgm
        : Cinema == true ? ListeningMode.Cinema
        : Bgm == false || Cinema == false ? ListeningMode.Standard
        : null;
}

internal sealed record PairedDevice(string Address, string Name, int LinkStatus, bool IsPlayback)
{
    public bool Connected => LinkStatus != 0;
}

internal sealed record PairedDevicesInfo(IReadOnlyList<PairedDevice> Devices, bool? PairingMode);

/// <summary>
/// Session MDR v2 : handshake puis échange de commandes. Port de MdrSession.kt, même logique :
/// chaque trame reçue fixe le numéro de séquence partagé, chaque trame de données est acquittée avec la
/// séquence inverse, les commandes partent avec la séquence courante.
/// </summary>
internal sealed class MdrSession : IDisposable
{
    private readonly Stream _input;
    private readonly Stream _output;
    private readonly MdrFrameReader _reader = new();
    private readonly BlockingCollection<byte[]> _chunks = new();
    private int _seq;
    private bool _hasTable2;
    private int? _pairingType;

    /// <summary>
    /// Dernière trame de données reçue. Quand le casque croit que son acquittement s'est perdu (liaison lente), il
    /// renvoie la trame à l'identique : la traiter comme une nouvelle réponse désynchronise la session.
    /// </summary>
    private MdrFrame? _lastData;

    private string? _model, _firmware;
    private int? _batteryLevel;
    private int _batteryCharging = 2;
    private NcAsmState? _nc;
    private int? _volume;

    /// <summary>Vrai une fois le handshake fait ; une session gardée ouverte ne le refait pas.</summary>
    public bool Handshaken { get; private set; }

    /// <summary>Dernières valeurs connues (handshake, réponses et notifications du casque), sans aucun échange.</summary>
    public BatteryReport? Report =>
        _batteryLevel is int level ? new BatteryReport(_model, _firmware, level, _batteryCharging, _nc, _volume) : null;

    /// <summary>Toute trame de données nouvelle (réponse ou notification), brute : sert au test des gestes.</summary>
    public event Action<int, byte[]>? FrameReceived;

    /// <summary>Reçoit une ligne par trame envoyée ou reçue (TX/RX).</summary>
    public Action<string>? Trace { get; set; }

    public MdrSession(Stream input, Stream output)
    {
        _input = input;
        _output = output;
        _ = Task.Run(PumpInput);
    }

    private async Task PumpInput()
    {
        var buf = new byte[1024];
        try
        {
            while (true)
            {
                int n = await _input.ReadAsync(buf);
                if (n <= 0) break;
                _chunks.Add(buf[..n]);
            }
        }
        catch { /* socket fermée */ }
        finally { _chunks.CompleteAdding(); }
    }

    public void Dispose()
    {
        try { _output.Dispose(); } catch { }
        try { _input.Dispose(); } catch { }
    }

    private static long Now => Environment.TickCount64;
    private static string Hex(byte[] b) => string.Join(" ", b.Select(x => x.ToString("X2")));

    // ------------------------------------------------------------------ envoi / réception

    private void Send(byte[] payload, int type = C.TypeData)
    {
        var frame = MdrFramer.Pack(type, _seq & 0xFF, payload);
        Trace?.Invoke($"TX type=0x{type:X2} seq={_seq & 0xFF} {Hex(payload)}");
        _output.Write(frame);
        _output.Flush();
    }

    private void SendT2(byte[] payload) => Send(payload, C.TypeDataNo2);

    private void SendAck(int seq)
    {
        _output.Write(MdrFramer.Pack(C.TypeAck, (1 - seq) & 0xFF, Array.Empty<byte>()));
        _output.Flush();
    }

    /// <summary>
    /// Écarte les trames en double (en les acquittant de nouveau) et retient batterie, bruit et volume annoncés
    /// par le casque. Ne renvoie que les trames nouvelles.
    /// </summary>
    private List<MdrFrame> TakeFresh(List<MdrFrame> frames)
    {
        var fresh = new List<MdrFrame>(frames.Count);
        foreach (var f in frames)
        {
            Trace?.Invoke($"RX type=0x{f.Type:X2} seq={f.Seq} {Hex(f.Payload)}");
            // Notre prochain numéro de séquence est celui du dernier ACQUITTEMENT reçu, pas celui de n'importe quelle
            // trame : les trames de données du casque (réponses, mais aussi notifications spontanées) ont leur propre
            // compteur. Après une notification les deux divergent, et une commande envoyée avec la séquence de la
            // précédente est ignorée sans même un acquittement.
            if (f.Type == C.TypeAck) _seq = f.Seq;
            if (f.Type is C.TypeData or C.TypeDataNo2)
            {
                if (_lastData is { } last && last.Type == f.Type && last.Seq == f.Seq && last.Payload.AsSpan().SequenceEqual(f.Payload))
                {
                    Trace?.Invoke($"RX doublon ignoré seq={f.Seq}");
                    SendAck(f.Seq);
                    continue;
                }
                _lastData = f;
            }
            Observe(f);
            if (f.Type is C.TypeData or C.TypeDataNo2) FrameReceived?.Invoke(f.Type, f.Payload);
            fresh.Add(f);
        }
        return fresh;
    }

    /// <summary>
    /// Geste physique annoncé par le casque dans son journal (« keyRRightFlick », « keyCustomBtnTwo »…), une fois le
    /// journal activé. Levé sur le fil de lecture de la session.
    /// </summary>
    public event Action<string>? GestureReceived;

    private string? _lastGesture;
    private long _lastGestureAt;

    /// <summary>Un même geste reçu deux fois à moins de 1,8 s est compté une fois (le casque l'a déjà renvoyé ainsi).</summary>
    private void ObserveLog(byte[] p)
    {
        var text = new string(p.Skip(2).Where(b => b >= 32 && b < 127).Select(b => (char)b).ToArray());
        if (!text.StartsWith("key", StringComparison.Ordinal)) return;
        long now = Now;
        if (text == _lastGesture && now - _lastGestureAt < 1800) return;
        _lastGesture = text;
        _lastGestureAt = now;
        GestureReceived?.Invoke(text);
    }

    /// <summary>
    /// Active le journal d'événements du casque (comme libmdr à l'initialisation) : sans cela il n'annonce aucun geste.
    /// </summary>
    public void EnableGestureLog()
    {
        Send(new byte[] { C.LogSetStatus, 0x01, 0x00 });
        AwaitAck(Now + 1000);
    }

    /// <summary>Dernière liste d'appareils appairés vue passer (réponse ou notification) et son compteur de version.</summary>
    public PairedDevicesInfo? LatestDevices { get; private set; }
    public int DevicesStamp { get; private set; }

    private void Observe(MdrFrame f)
    {
        var p = f.Payload;
        if (f.Type == C.TypeDataNo2 && p.Length >= 3 && p[0] is C.PeriRetParam or C.PeriNtfyParam
            && (p[1] == C.PeriTypePairingWithCod || p[1] == C.PeriTypePairingClassicBt))
        {
            try
            {
                LatestDevices = new PairedDevicesInfo(ParsePairedDevices(p, p[1] == C.PeriTypePairingWithCod), LatestDevices?.PairingMode);
                DevicesStamp++;
            }
            catch (MdrException) { /* notification tronquée : on garde la liste précédente */ }
            return;
        }
        if (f.Type != C.TypeData || p.Length < 3) return;
        if (p[0] == C.LogNtfyParam)
        {
            ObserveLog(p);
            return;
        }
        if (p[0] is C.PowerRetStatus or C.PowerNtfyStatus && p[1] == C.PowerBattery && p.Length >= 4)
        {
            _batteryLevel = p[2];
            _batteryCharging = p[3];
        }
        else if (p[0] is C.NcAsmRetParam or C.NcAsmNtfyParam && p[1] == C.NcAsmTypeDualNa && p.Length >= 7)
        {
            _nc = ParseNcAsm(p);
        }
        else if (p[0] is C.NcAsmRetParam or C.NcAsmNtfyParam && p[1] == C.NcAsmTypeButton)
        {
            _settings = _settings with { NcButton = p[2] };
        }
        else if (p[0] is C.PlayRetParam or C.PlayNtfyParam && p[1] == C.PlayTypeMusicVolume)
        {
            _volume = p[2];
        }
        else if (p[0] is C.SystemRetParam or C.SystemNtfyParam && p[1] == C.SystemTypePlaybackByWearing)
        {
            _settings = _settings with { PauseWhenRemoved = p[2] == C.V2Enable };
        }
        else if (p[0] is C.SystemRetParam or C.SystemNtfyParam && p[1] == C.SystemTypeSmartTalking)
        {
            _settings = _settings with { SpeakToChat = p[2] == C.V2Enable };
        }
        else if (p[0] is C.AudioRetParam or C.AudioNtfyParam && p[1] == C.AudioTypeUpscaling)
        {
            _settings = _settings with { Dsee = p[2] == C.UpscalingAuto };
        }
        else if (p[0] is C.AudioRetParam or C.AudioNtfyParam && p[1] == C.AudioTypeBgm)
        {
            _settings = _settings with { Bgm = p[2] == C.V2Enable, BgmRoom = p.Length > 3 ? p[3] : _settings.BgmRoom };
        }
        else if (p[0] is C.AudioRetParam or C.AudioNtfyParam && p[1] == C.AudioTypeCinema)
        {
            _settings = _settings with { Cinema = p[2] == C.V2Enable };
        }
    }

    /// <summary>
    /// Lit et acquitte ce qui est déjà arrivé, sans attendre. À appeler régulièrement sur une session gardée
    /// ouverte : le casque envoie des notifications spontanées et renvoie ce qu'on n'acquitte pas.
    /// Lève une exception si la liaison est coupée.
    /// </summary>
    public void Drain()
    {
        while (_chunks.TryTake(out var chunk)) _reader.Feed(chunk);
        foreach (var f in TakeFresh(_reader.ReadAvailable()))
        {
            if (f.Type is C.TypeData or C.TypeDataNo2) SendAck(f.Seq);
        }
        if (_chunks.IsCompleted) throw new EndOfStreamException("Flux fermé par le casque");
    }

    private List<MdrFrame> ReadFrames(long deadline, string what)
    {
        while (true)
        {
            var frames = TakeFresh(_reader.ReadAvailable());
            if (frames.Count > 0) return frames;
            long left = deadline - Now;
            if (left <= 0) throw new MdrException($"Délai dépassé : {what}");
            if (_chunks.TryTake(out var chunk, (int)Math.Min(left, 100))) _reader.Feed(chunk);
            else if (_chunks.IsCompleted) throw new EndOfStreamException("Flux fermé par le casque");
        }
    }

    /// <summary>
    /// Attend une trame de [frameType] acceptée par [accept]. Toutes les trames reçues sont acquittées
    /// (y compris celles qui suivent la trame cherchée dans le même lot) ; les autres sont ignorées.
    /// </summary>
    private byte[] Await(string what, long deadline, Func<byte[], bool> accept, int frameType = C.TypeData)
    {
        while (true)
        {
            byte[]? match = null;
            foreach (var f in ReadFrames(deadline, what))
            {
                if (f.Type is C.TypeData or C.TypeDataNo2) SendAck(f.Seq);
                if (match == null && f.Type == frameType && f.Payload.Length >= 2 && accept(f.Payload)) match = f.Payload;
            }
            if (match != null) return match;
        }
    }

    private static bool Cmd(byte[] p, int cmd) => p[0] == cmd;
    private static bool CmdType(byte[] p, int cmd, int type) => p[0] == cmd && p[1] == type;
    private static bool CmdType(byte[] p, int cmd1, int cmd2, int type) => (p[0] == cmd1 || p[0] == cmd2) && p[1] == type;

    // ------------------------------------------------------------------ handshake

    public BatteryReport HandshakeAndBattery()
    {
        long deadline = Now + C.HandshakeTimeoutMs;

        // Le XM6 répond sans handshake (essayé) et chaque échange coûte 0,4 à 0,9 s : on ne demande ni les
        // capacités, ni les fonctions, ni « série et couleur », et l'info de protocole (qui dit si la table 2
        // multipoint existe) attend qu'on en ait besoin. Firmware et modèle ne sont lus que s'ils ne sont pas
        // déjà connus (SeedDeviceInfo). Le type d'info est vérifié : une réponse en double ne doit pas être prise
        // pour une autre.
        _firmware ??= DeviceInfo(C.DeviceInfoFwVersion, "version du firmware", deadline);
        _model ??= DeviceInfo(C.DeviceInfoModelName, "nom du modèle", deadline);

        Send(new byte[] { C.PowerGetStatus, C.PowerBattery });
        var bat = Await("batterie", deadline, p => CmdType(p, C.PowerRetStatus, C.PowerBattery));
        if (bat.Length < 4) throw new MdrException("Réponse batterie inattendue");

        try { ReadNcAsm(deadline); } catch (MdrException) { /* facultatif */ }
        try { ReadMusicVolume(); } catch (MdrException) { /* facultatif */ }

        EnableGestureLog();

        Handshaken = true;
        return Report!;
    }

    private string? DeviceInfo(int infoType, string what, long deadline)
    {
        Send(new byte[] { C.ConnectGetDeviceInfo, (byte)infoType });
        var ret = Await(what, deadline, p => CmdType(p, C.ConnectRetDeviceInfo, infoType));
        if (ret.Length < 3) return null;
        int len = ret[2];
        return ret.Length < 3 + len ? null : Encoding.UTF8.GetString(ret, 3, len);
    }

    // ------------------------------------------------------------------ bruit / ambiant / volume / lecture

    private NcAsmState ReadNcAsm(long deadline)
    {
        Send(new byte[] { C.NcAsmGetParam, C.NcAsmTypeDualNa });
        return ParseNcAsm(Await("réduction de bruit", deadline, p => p[0] is C.NcAsmRetParam or C.NcAsmNtfyParam));
    }

    private static NcAsmState ParseNcAsm(byte[] p)
    {
        if (p.Length < 7) throw new MdrException("Réponse NC/ASM inattendue");
        return new NcAsmState(
            Enabled: p[3] == C.NcAsmOn,
            Mode: p[4],
            AmbientLevel: p[6],
            Auto: p.Length >= 9 && p[7] == C.NcAsmOn,
            Sensitivity: p.Length >= 9 ? p[8] : C.NcAsmSensitivityStandard);
    }

    /// <summary>Mode : 0 = réduction de bruit, 1 = ambiant, 2 = désactivé.</summary>
    public NcAsmState SetNcAsm(int mode, int ambientLevel = C.AmbientLevelDefault, bool auto = false)
    {
        bool enabled = mode != 2;
        int asmMode = mode == 1 ? C.NcAsmModeAsm : C.NcAsmModeNc;
        int level = mode == 1 ? ambientLevel : 0;
        return SendNcAsm(enabled, asmMode, level, auto);
    }

    public NcAsmState SetAmbientLevel(int level, bool auto) => SendNcAsm(true, C.NcAsmModeAsm, level, auto);

    private NcAsmState SendNcAsm(bool enabled, int mode, int level, bool auto)
    {
        long deadline = Now + C.HandshakeTimeoutMs;
        Send(new byte[]
        {
            C.NcAsmSetParam, C.NcAsmTypeDualNa, C.NcAsmStatusChanged,
            (byte)(enabled ? C.NcAsmOn : C.NcAsmOff), (byte)mode, C.NcAsmAmbientNormal,
            (byte)level, (byte)(auto ? C.NcAsmOn : C.NcAsmOff), C.NcAsmSensitivityStandard,
        });
        return ParseNcAsm(Await("réduction de bruit", deadline, p => p[0] is C.NcAsmRetParam or C.NcAsmNtfyParam));
    }

    public int? ReadMusicVolume()
    {
        long deadline = Now + C.SocketTimeoutMs;
        Send(new byte[] { C.PlayGetParam, C.PlayTypeMusicVolume });
        try
        {
            var ret = Await("volume", deadline, p => p.Length >= 3 && CmdType(p, C.PlayRetParam, C.PlayNtfyParam, C.PlayTypeMusicVolume));
            return ret[2];
        }
        catch (MdrException) { return null; }
    }

    public int SetMusicVolume(int volume)
    {
        int v = Math.Clamp(volume, 0, C.PlayVolumeMax);
        long deadline = Now + C.HandshakeTimeoutMs;
        Send(new byte[] { C.PlaySetParam, C.PlayTypeMusicVolume, (byte)v });
        Await("réponse volume", deadline, p => p[0] is C.PlayRetParam or C.PlayNtfyParam);
        return v;
    }

    /// <summary>Lecture, pause, piste suivante ou précédente (sans attendre de retour).</summary>
    public void PlayControl(int control) =>
        Send(new byte[] { C.PlaySetStatus, C.PlayTypePlayback, C.V2Enable, (byte)control });

    // ------------------------------------------------------------------ égaliseur

    public EqState? ReadEq()
    {
        long deadline = Now + C.SocketTimeoutMs;
        Send(new byte[] { C.EqGetParam, C.EqTypeXm6 });
        try { return ParseEq(AwaitEq("lecture de l'égaliseur", deadline)); }
        catch (MdrException) { return null; }
    }

    public EqState? SetEqPreset(int preset) =>
        WriteEq(new byte[] { C.EqSetParam, C.EqTypeXm6, (byte)preset, 0x00 });

    /// <summary>Courbe personnalisée : 10 gains de -6 à +6 dB, graves en premier.</summary>
    public EqState? SetEqBands(IReadOnlyList<int> gains)
    {
        if (gains.Count != C.EqBandCount) throw new ArgumentException("L'égaliseur XM6 a 10 bandes");
        var payload = new byte[4 + gains.Count];
        payload[0] = C.EqSetParam;
        payload[1] = C.EqTypeXm6;
        payload[2] = C.EqCustom;
        payload[3] = (byte)gains.Count;
        for (int i = 0; i < gains.Count; i++)
            payload[4 + i] = (byte)(Math.Clamp(gains[i], C.EqGainMin, C.EqGainMax) + C.EqBandOffset);
        return WriteEq(payload);
    }

    /// <summary>
    /// Notification ou acquittement, le premier des deux : le XM6 n'envoie la notification d'état que pour un
    /// changement de préréglage, et sans elle on relit tout de suite au lieu d'attendre la fin du délai.
    /// </summary>
    private EqState? WriteEq(byte[] payload)
    {
        Send(payload);
        long deadline = Now + C.EqEchoWaitMs;
        bool acked = false;
        byte[]? echo = null;
        while (!acked && echo == null)
        {
            List<MdrFrame> frames;
            try { frames = ReadFrames(deadline, "réponse de l'égaliseur"); }
            catch (MdrException) { break; }
            foreach (var f in frames)
            {
                if (f.Type is C.TypeData or C.TypeDataNo2) SendAck(f.Seq);
                if (f.Type == C.TypeAck) acked = true;
                if (echo == null && f.Type == C.TypeData && f.Payload.Length >= 4 && CmdType(f.Payload, C.EqRetParam, C.EqNtfyParam, C.EqTypeXm6))
                    echo = f.Payload;
            }
        }
        return echo != null ? ParseEq(echo) : ReadEq();
    }
    private byte[] AwaitEq(string what, long deadline) =>
        Await(what, deadline, p => p.Length >= 4 && CmdType(p, C.EqRetParam, C.EqNtfyParam, C.EqTypeXm6));

    private static EqState ParseEq(byte[] p)
    {
        int preset = p[2];
        int count = p[3];
        var bands = new List<int>();
        if (count > 0 && p.Length >= 4 + count)
        {
            int offset = count == 6 ? 10 : C.EqBandOffset;
            for (int i = 0; i < count; i++) bands.Add(p[4 + i] - offset);
        }
        return new EqState(preset, bands);
    }

    // ------------------------------------------------------------------ réglages

    private enum SettingKind { Wear, Chat, Dsee, Bgm, Cinema, NcButton }

    /// <summary>Dernier état connu des réglages : lectures, réponses et notifications du casque (voir Observe).</summary>
    private SettingsState _settings = new(null, null, null, null, null, null);

    public SettingsState Settings => _settings;

    private byte[]? ReadParam(int getCmd, int ret1, int ret2, int type)
    {
        long deadline = Now + C.SettingReadWaitMs;
        Send(new byte[] { (byte)getCmd, (byte)type });
        try { return Await($"lecture du réglage 0x{type:X2}", deadline, p => p.Length >= 3 && CmdType(p, ret1, ret2, type)); }
        catch (MdrException) { return null; }
    }

    /// <summary>Lit seulement les réglages demandés ; chaque réponse met le cache à jour.</summary>
    private void ReadSettingKinds(params SettingKind[] kinds)
    {
        foreach (var kind in kinds)
        {
            switch (kind)
            {
                case SettingKind.Wear: ReadParam(C.SystemGetParam, C.SystemRetParam, C.SystemNtfyParam, C.SystemTypePlaybackByWearing); break;
                case SettingKind.Chat: ReadParam(C.SystemGetParam, C.SystemRetParam, C.SystemNtfyParam, C.SystemTypeSmartTalking); break;
                case SettingKind.Dsee: ReadParam(C.AudioGetParam, C.AudioRetParam, C.AudioNtfyParam, C.AudioTypeUpscaling); break;
                case SettingKind.Bgm: ReadParam(C.AudioGetParam, C.AudioRetParam, C.AudioNtfyParam, C.AudioTypeBgm); break;
                case SettingKind.Cinema: ReadParam(C.AudioGetParam, C.AudioRetParam, C.AudioNtfyParam, C.AudioTypeCinema); break;
                case SettingKind.NcButton: ReadParam(C.NcAsmGetParam, C.NcAsmRetParam, C.NcAsmNtfyParam, C.NcAsmTypeButton); break;
            }
        }
    }

    public SettingsState ReadSettings()
    {
        ReadSettingKinds(SettingKind.Wear, SettingKind.Chat, SettingKind.Dsee, SettingKind.Bgm, SettingKind.Cinema, SettingKind.NcButton);
        return _settings;
    }

    /// <summary>
    /// Modes que le bouton de bruit (oreillette gauche) fait défiler : C.NcButtonAll (bruit, ambiant, désactivé) ou
    /// C.NcButtonNcAsm (bruit et ambiant), essayés sur le casque ; NcOff et AsmOff viennent de libmdr, non essayés.
    /// </summary>
    public SettingsState SetNcButton(int function)
    {
        WriteParam(new byte[] { C.NcAsmSetParam, C.NcAsmTypeButton, (byte)function });
        ConfirmSettings(s => s.NcButton == function, SettingKind.NcButton);
        return _settings;
    }

    /// <summary>
    /// Envoie une écriture et n'attend que l'ACQUITTEMENT du casque, pas sa notification : elle ne vient pas toujours,
    /// et l'attendre jusqu'à 1,5 s à chaque écriture rendait les réglages lents.
    /// </summary>
    private void WriteParam(byte[] payload)
    {
        Send(payload);
        AwaitAck(Now + C.SettingReplyWaitMs);
    }

    private void AwaitAck(long deadline)
    {
        while (true)
        {
            List<MdrFrame> frames;
            try { frames = ReadFrames(deadline, "acquittement"); }
            catch (MdrException) { return; }
            bool acked = false;
            foreach (var f in frames)
            {
                if (f.Type is C.TypeData or C.TypeDataNo2) SendAck(f.Seq);
                if (f.Type == C.TypeAck) acked = true;
            }
            if (acked) return;
        }
    }

    /// <summary>
    /// Après une écriture, attend que les notifications du casque confirment le nouvel état (le cache se met à jour à
    /// chaque trame reçue), au plus 1,2 s ; sinon relit explicitement. Après un changement de mode d'écoute le casque
    /// envoie une rafale de notifications qui retarde ses réponses, alors qu'elles portent déjà l'état.
    /// </summary>
    private void ConfirmSettings(Func<SettingsState, bool> expected, params SettingKind[] kinds)
    {
        long deadline = Now + C.SettingConfirmWaitMs;
        while (!expected(_settings))
        {
            try
            {
                foreach (var f in ReadFrames(deadline, "confirmation du réglage"))
                    if (f.Type is C.TypeData or C.TypeDataNo2) SendAck(f.Seq);
            }
            catch (MdrException)
            {
                ReadSettingKinds(kinds);
                return;
            }
        }
    }

    private static byte V2(bool on) => (byte)(on ? C.V2On : C.V2Off);

    public SettingsState SetPauseWhenRemoved(bool on)
    {
        WriteParam(new byte[] { C.SystemSetParam, C.SystemTypePlaybackByWearing, V2(on) });
        ConfirmSettings(s => s.PauseWhenRemoved == on, SettingKind.Wear);
        return _settings;
    }

    public SettingsState SetSpeakToChat(bool on)
    {
        WriteParam(new byte[] { C.SystemSetParam, C.SystemTypeSmartTalking, V2(on), C.V2Off });
        ConfirmSettings(s => s.SpeakToChat == on, SettingKind.Chat);
        return _settings;
    }

    public SettingsState SetDsee(bool on)
    {
        WriteParam(new byte[] { C.AudioSetParam, C.AudioTypeUpscaling, (byte)(on ? C.UpscalingAuto : C.UpscalingOff) });
        ConfirmSettings(s => s.Dsee == on, SettingKind.Dsee);
        return _settings;
    }

    /// <summary>
    /// Musique de fond et cinéma s'excluent : on éteint l'autre avant d'allumer le choisi. Un réglage déjà éteint
    /// d'après le dernier état connu n'est pas réécrit (chaque échange coûte du temps).
    /// </summary>
    public SettingsState SetListeningMode(ListeningMode mode, int room = C.RoomSmall)
    {
        var known = _settings;
        switch (mode)
        {
            case ListeningMode.Standard:
                if (known.Bgm != false) WriteBgm(false, room);
                if (known.Cinema != false) WriteCinema(false);
                break;
            case ListeningMode.Bgm:
                if (known.Cinema != false) WriteCinema(false);
                WriteBgm(true, room);
                break;
            case ListeningMode.Cinema:
                if (known.Bgm != false) WriteBgm(false, room);
                WriteCinema(true);
                break;
        }
        ConfirmSettings(s => mode switch
        {
            ListeningMode.Standard => s.Bgm == false && s.Cinema == false,
            ListeningMode.Bgm => s.Bgm == true && s.Cinema == false,
            _ => s.Cinema == true && s.Bgm == false,
        }, SettingKind.Bgm, SettingKind.Cinema);
        return _settings;
    }

    private void WriteBgm(bool on, int room) =>
        WriteParam(new byte[] { C.AudioSetParam, C.AudioTypeBgm, V2(on), (byte)room });

    private void WriteCinema(bool on) =>
        WriteParam(new byte[] { C.AudioSetParam, C.AudioTypeCinema, V2(on) });

    // ------------------------------------------------------------------ boutons assignables (lecture seule)

    /// <summary>
    /// Sonde des boutons assignables : réponses brutes du casque aux deux types candidats (0x03 et 0x0E, avec
    /// limitation), null quand il ne répond pas. Ne modifie rien sur le casque. Le format XM6 n'est pas confirmé.
    /// </summary>
    public (byte[]? Plain, byte[]? Ext, byte[]? ExtLimited) ProbeAssignable() =>
        (ReadParam(C.SystemGetParam, C.SystemRetParam, C.SystemNtfyParam, C.SystemTypeAssignable),
         ReadParam(C.SystemGetExtParam, C.SystemRetExtParam, C.SystemNtfyExtParam, C.SystemTypeAssignable),
         ReadParam(C.SystemGetExtParam, C.SystemRetExtParam, C.SystemNtfyExtParam, C.SystemTypeAssignableLimit));

    /// <summary>Éteint le casque ; il ne répond pas, la liaison se coupe.</summary>
    public void PowerOff() => Send(new byte[] { C.PowerSetStatus, C.PowerTypePowerOff, C.PowerOffUser });

    // ------------------------------------------------------------------ multipoint (table 2)

    private byte[] AwaitT2(string what, long deadline, Func<byte[], bool> accept) =>
        Await(what, deadline, accept, C.TypeDataNo2);

    /// <summary>Retient un firmware et un modèle déjà connus (mémorisés), pour ne pas les redemander au casque.</summary>
    public void SeedDeviceInfo(string? model, string? firmware)
    {
        _model = model;
        _firmware = firmware;
    }

    /// <summary>Relit firmware et modèle (2 échanges) : garde la mémoire à jour après une mise à jour du casque.</summary>
    public (string? Model, string? Firmware) RefreshDeviceInfo()
    {
        long deadline = Now + C.HandshakeTimeoutMs;
        _firmware = DeviceInfo(C.DeviceInfoFwVersion, "version du firmware", deadline);
        _model = DeviceInfo(C.DeviceInfoModelName, "nom du modèle", deadline);
        return (_model, _firmware);
    }

    private bool _protocolKnown;

    private void EnsureProtocolInfo()
    {
        if (_protocolKnown) return;
        long deadline = Now + C.HandshakeTimeoutMs;
        Send(new byte[] { C.ConnectGetProtocolInfo, 0x00 });
        var proto = Await("info de protocole", deadline, p => Cmd(p, C.ConnectRetProtocolInfo));
        // [cmd, type, version(4), table1, table2] ; ENABLE = 0x00 en v2.
        _hasTable2 = proto.Length > 7 && proto[7] == C.V2Enable;
        _protocolKnown = true;
    }

    private int EnsurePairingType()
    {
        if (_pairingType is int cached)
            return cached >= 0 ? cached : throw new MdrException(NoMultipoint);
        EnsureProtocolInfo();
        if (!_hasTable2) { _pairingType = -1; throw new MdrException(NoMultipoint); }

        long deadline = Now + C.HandshakeTimeoutMs;
        SendT2(new byte[] { C.ConnectGetSupportFunction, 0x00 });
        var ret = AwaitT2("fonctions de la table 2", deadline, p => Cmd(p, C.ConnectRetSupportFunction));
        // [cmd, type, count, (fonction, priorité) * count]
        int count = ret.Length > 2 ? ret[2] : 0;
        var functions = new HashSet<int>();
        for (int i = 0; i < count && 3 + i * 2 < ret.Length; i++) functions.Add(ret[3 + i * 2]);
        int type =
            functions.Contains(C.T2FuncPairingCodClassicBt) || functions.Contains(C.T2FuncPairingCodClassicLe) ? C.PeriTypePairingWithCod
            : functions.Contains(C.T2FuncPairingClassicBt) ? C.PeriTypePairingClassicBt
            : -1;
        _pairingType = type;
        return type >= 0 ? type : throw new MdrException(NoMultipoint);
    }

    private const string NoMultipoint = "Ce casque n'expose pas la gestion multipoint (table 2).";

    public PairedDevicesInfo ReadPairedDevices()
    {
        int type = EnsurePairingType();
        SendT2(new byte[] { C.PeriGetParam, (byte)type });
        var ret = AwaitT2("appareils appairés", Now + C.HandshakeTimeoutMs,
            p => CmdType(p, C.PeriRetParam, C.PeriNtfyParam, type));
        var devices = ParsePairedDevices(ret, type == C.PeriTypePairingWithCod);

        bool? pairing = null;
        try
        {
            SendT2(new byte[] { C.PeriGetStatus, (byte)type });
            var st = AwaitT2("mode d'appairage", Now + C.SocketTimeoutMs,
                p => CmdType(p, C.PeriRetStatus, C.PeriNtfyStatus, type));
            if (st.Length >= 4) pairing = st[3] == C.V2Enable && st[2] == C.BtModeInquiryScan;
        }
        catch (MdrException) { /* facultatif */ }
        return new PairedDevicesInfo(devices, pairing);
    }

    private static List<PairedDevice> ParsePairedDevices(byte[] ret, bool withCod)
    {
        void Need(int at, int n) { if (at + n > ret.Length) throw new MdrException("Liste d'appareils tronquée"); }
        Need(2, 1);
        int count = ret[2];
        int pos = 3;
        var raws = new List<(string Mac, int Status, string Name)>();
        for (int i = 0; i < count; i++)
        {
            Need(pos, C.MacAddressLength + 1);
            string mac = Encoding.ASCII.GetString(ret, pos, C.MacAddressLength);
            pos += C.MacAddressLength;
            int status = ret[pos++];
            if (withCod) { Need(pos, 3); pos += 3; }
            Need(pos, 1);
            int len = ret[pos++];
            Need(pos, len);
            raws.Add((mac, status, Encoding.UTF8.GetString(ret, pos, len)));
            pos += len;
        }
        Need(pos, 1);
        int playback = ret[pos];
        return raws.Select(r => new PairedDevice(r.Mac.ToUpperInvariant(), r.Name, r.Status, r.Status != 0 && r.Status == playback)).ToList();
    }

    public int ConnectPairedDevice(string address) => PeripheralAction(C.ConnActionConnect, address);
    public int DisconnectPairedDevice(string address) => PeripheralAction(C.ConnActionDisconnect, address);

    private int PeripheralAction(int action, string address)
    {
        int type = EnsurePairingType();
        long deadline = Now + C.PeripheralActionTimeoutMs;
        SendT2(new byte[] { C.PeriSetExtParam, (byte)type, (byte)action }.Concat(MacBytes(address)).ToArray());
        // [cmd, type, action, résultat, mac] ; on attend la fin de l'opération (pas « en cours »).
        var ret = AwaitT2("résultat de l'opération", deadline, p =>
            p[0] == C.PeriNtfyExtParam && p.Length >= 4 && p[1] == type && p[2] == action && (p[3] & 0x0F) != C.PeripheralInProgress);
        return ret[3];
    }

    /// <summary>Bascule la lecture audio vers un appareil déjà connecté. Renvoie 0 en cas de succès.</summary>
    public int SwitchSource(string address)
    {
        EnsurePairingType();
        long deadline = Now + C.PeripheralActionTimeoutMs;
        SendT2(new byte[] { C.PeriSetExtParam, C.PeriTypeSourceSwitch }.Concat(MacBytes(address)).ToArray());
        var ret = AwaitT2("changement de source", deadline,
            p => p[0] == C.PeriNtfyExtParam && p.Length >= 3 && p[1] == C.PeriTypeSourceSwitch);
        return ret[2];
    }

    private static byte[] MacBytes(string address)
    {
        var bytes = Encoding.ASCII.GetBytes(address.ToUpperInvariant());
        return bytes.Length == C.MacAddressLength ? bytes : throw new MdrException($"Adresse Bluetooth invalide : {address}");
    }
}
