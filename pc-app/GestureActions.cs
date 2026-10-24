using System.Diagnostics;
using System.Runtime.InteropServices;

namespace XmRipPc;

internal enum GestureAction { None, PlayPause, Next, Previous, Launch }

/// <summary>Action associée à un geste du casque ; <see cref="Target"/> ne sert que pour <see cref="GestureAction.Launch"/>.</summary>
internal sealed record GestureBinding(GestureAction Action, string Target);

/// <summary>
/// Actions exécutées sur le PC quand le casque annonce un geste (double et triple appui du bouton de bruit).
/// Mémorisées dans %LOCALAPPDATA%\XmRipPc\gestes.txt, une ligne par geste : nom|action|cible.
/// </summary>
internal static class GestureSettings
{
    public const string DoubleButton = "keyCustomBtnTwo", TripleButton = "keyCustomBtnThree";

    private static readonly string File_ =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "XmRipPc", "gestes.txt");

    private static readonly Dictionary<string, GestureBinding> Bindings = new();

    /// <summary>Vrai dès qu'un geste a une action : la liaison avec le casque doit alors rester ouverte pour l'entendre.</summary>
    public static bool Any
    {
        get { lock (Bindings) return Bindings.Values.Any(b => b.Action != GestureAction.None); }
    }

    public static GestureBinding Get(string gesture)
    {
        lock (Bindings) return Bindings.TryGetValue(gesture, out var b) ? b : new GestureBinding(GestureAction.None, "");
    }

    public static void Set(string gesture, GestureBinding binding)
    {
        lock (Bindings) Bindings[gesture] = binding;
        Save();
    }

    public static void Load()
    {
        try
        {
            lock (Bindings)
            {
                Bindings.Clear();
                foreach (var line in File.ReadAllLines(File_))
                {
                    var parts = line.Split('|', 3);
                    if (parts.Length == 3 && Enum.TryParse<GestureAction>(parts[1], out var action))
                        Bindings[parts[0]] = new GestureBinding(action, parts[2]);
                }
            }
        }
        catch { /* pas de réglage mémorisé */ }
    }

    private static void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(File_)!);
            lock (Bindings)
                File.WriteAllLines(File_, Bindings.Select(kv => $"{kv.Key}|{kv.Value.Action}|{kv.Value.Target}"));
        }
        catch { /* mémoire facultative */ }
    }

    /// <summary>Exécute l'action ; renvoie un message d'erreur, ou null si tout s'est bien passé.</summary>
    public static string? Run(GestureBinding binding)
    {
        try
        {
            switch (binding.Action)
            {
                case GestureAction.PlayPause: PressMediaKey(0xB3); break;
                case GestureAction.Next: PressMediaKey(0xB0); break;
                case GestureAction.Previous: PressMediaKey(0xB1); break;
                case GestureAction.Launch:
                    if (string.IsNullOrWhiteSpace(binding.Target)) return "Aucun programme ni lien indiqué.";
                    Process.Start(new ProcessStartInfo(binding.Target.Trim()) { UseShellExecute = true });
                    break;
            }
            return null;
        }
        catch (Exception e)
        {
            return e.Message;
        }
    }

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);

    private const uint KeyUp = 0x0002;

    private static void PressMediaKey(byte vk)
    {
        keybd_event(vk, 0, 0, UIntPtr.Zero);
        keybd_event(vk, 0, KeyUp, UIntPtr.Zero);
    }

    /// <summary>Nom lisible d'un geste annoncé par le casque.</summary>
    public static string Describe(string gesture) => gesture switch
    {
        "keyRRightFlick" => "glisser vers la droite",
        "keyRLeftFlick" => "glisser vers la gauche",
        "keyRUpFlick" => "glisser vers le haut",
        "keyRDownFlick" => "glisser vers le bas",
        "keyRDoubleTap" => "double appui sur la zone tactile",
        DoubleButton => "double appui sur le bouton de bruit",
        TripleButton => "triple appui sur le bouton de bruit",
        _ => gesture,
    };
}
