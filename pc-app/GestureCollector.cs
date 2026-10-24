using System.Globalization;
using System.Text;

namespace XmRipPc;

/// <summary>
/// Test des gestes du casque : pendant une fenêtre de quelques secondes, retient tout ce que le casque envoie de
/// lui-même (messages bruts) pour voir si un geste, par exemple un tap sur la zone tactile, arrive jusqu'à l'appli.
/// Les réponses à nos propres demandes (protocole, batterie, bruit, volume) sont écartées.
/// </summary>
internal sealed class GestureCollector
{
    private sealed record Msg(double AtSeconds, int Type, byte[] Payload);

    private static readonly HashSet<int> Replies = new() { 0x01, 0x23, 0x67, 0xA7 };

    private readonly object _lock = new();
    private readonly List<Msg> _messages = new();
    private string? _label;
    private DateTime _startedAt;

    public bool Active
    {
        get { lock (_lock) return _label != null; }
    }

    public void Begin(string label)
    {
        lock (_lock)
        {
            _label = label;
            _startedAt = DateTime.UtcNow;
            _messages.Clear();
        }
    }

    /// <summary>Appelé pour chaque trame de données nouvelle reçue (fil de lecture, pas le fil d'interface).</summary>
    public void OnFrame(int type, byte[] payload)
    {
        lock (_lock)
        {
            if (_label == null || payload.Length == 0) return;
            if (type == 0x0C && Replies.Contains(payload[0])) return;
            _messages.Add(new Msg((DateTime.UtcNow - _startedAt).TotalSeconds, type, payload));
        }
    }

    /// <summary>Termine la fenêtre et renvoie le compte rendu.</summary>
    public string Finish(bool linkLost)
    {
        lock (_lock)
        {
            var name = _label ?? "";
            _label = null;
            var sb = new StringBuilder();
            sb.Append("Geste « ").Append(name).Append(" » : ");
            if (_messages.Count == 0) sb.Append("aucun message du casque");
            else sb.Append(_messages.Count).Append(" message(s)");
            foreach (var m in _messages)
            {
                sb.AppendLine().Append("  +").Append(m.AtSeconds.ToString("0.0", CultureInfo.InvariantCulture)).Append(" s  ");
                sb.Append(m.Type == 0x0E ? "[table 2] " : "");
                sb.Append(string.Join(" ", m.Payload.Select(b => b.ToString("X2"))));
                if (Describe(m.Type, m.Payload) is { } d) sb.Append("  (").Append(d).Append(')');
            }
            if (linkLost)
                sb.AppendLine().Append("  ! la liaison avec le casque a été coupée pendant la fenêtre (un autre appareil a pris la session) : résultat à refaire");
            return sb.ToString();
        }
    }

    /// <summary>Nom probable d'un message, pour la lecture ; null s'il est inconnu.</summary>
    private static string? Describe(int type, byte[] p)
    {
        if (p.Length < 2) return null;
        int cmd = p[0], t = p[1];
        if (type == 0x0E) return cmd is 0x37 or 0x39 ? "appareils connectés" : null;
        return cmd switch
        {
            0xA5 when t == 0x01 => "état de la lecture",
            0xA9 when t == 0x20 => "volume",
            0xA9 when t == 0x01 => "titre en cours",
            0xE5 or 0xE9 => "réglage audio",
            0x25 => "batterie",
            0x69 => "réduction de bruit",
            0x59 => "égaliseur",
            0xF9 => "réglage système",
            _ => null,
        };
    }
}
