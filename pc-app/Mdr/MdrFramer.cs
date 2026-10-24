namespace XmRipPc.Mdr;

internal sealed record MdrFrame(int Type, int Seq, byte[] Payload);

/// <summary>Encodage et décodage des trames : START 0x3E, END 0x3C, escape 0x3D, somme de contrôle modulo 256.</summary>
internal static class MdrFramer
{
    public static byte[] Pack(int type, int seq, byte[] payload)
    {
        var body = new byte[6 + payload.Length + 1];
        body[0] = (byte)type;
        body[1] = (byte)seq;
        body[2] = (byte)(payload.Length >> 24);
        body[3] = (byte)(payload.Length >> 16);
        body[4] = (byte)(payload.Length >> 8);
        body[5] = (byte)payload.Length;
        payload.CopyTo(body, 6);
        int sum = 0;
        for (int i = 0; i < body.Length - 1; i++) sum = (sum + body[i]) & 0xFF;
        body[^1] = (byte)sum;

        var o = new List<byte>(body.Length * 2 + 2) { (byte)C.Start };
        foreach (var b in body)
        {
            switch (b)
            {
                case C.End: o.Add(C.Escape); o.Add(C.Esc3C); break;
                case C.Escape: o.Add(C.Escape); o.Add(C.Esc3D); break;
                case C.Start: o.Add(C.Escape); o.Add(C.Esc3E); break;
                default: o.Add(b); break;
            }
        }
        o.Add((byte)C.End);
        return o.ToArray();
    }

    public static MdrFrame? Unpack(byte[] frame)
    {
        if (frame.Length < 8 || frame[0] != C.Start || frame[^1] != C.End) return null;
        var body = new List<byte>(frame.Length);
        for (int i = 1; i < frame.Length - 1; i++)
        {
            if (frame[i] != C.Escape) { body.Add(frame[i]); continue; }
            if (i + 1 >= frame.Length - 1) return null;
            switch (frame[++i])
            {
                case C.Esc3C: body.Add(C.End); break;
                case C.Esc3D: body.Add(C.Escape); break;
                case C.Esc3E: body.Add(C.Start); break;
                default: return null;
            }
        }
        if (body.Count < 7) return null;
        long size = ((long)body[2] << 24) | ((long)body[3] << 16) | ((long)body[4] << 8) | body[5];
        if (body.Count != 6 + size + 1) return null;
        int sum = 0;
        for (int i = 0; i < body.Count - 1; i++) sum = (sum + body[i]) & 0xFF;
        if (sum != body[^1]) return null;
        return new MdrFrame(body[0], body[1], body.GetRange(6, (int)size).ToArray());
    }
}

/// <summary>Reconstruit les trames depuis un flux d'octets continu ; les trames malformées sont ignorées.</summary>
internal sealed class MdrFrameReader
{
    private readonly List<byte> _buf = new();

    public void Feed(ReadOnlySpan<byte> chunk) => _buf.AddRange(chunk.ToArray());

    public List<MdrFrame> ReadAvailable()
    {
        var frames = new List<MdrFrame>();
        while (true)
        {
            int start = _buf.IndexOf((byte)C.Start);
            if (start < 0) { _buf.Clear(); break; }
            if (start > 0) _buf.RemoveRange(0, start);
            int end = _buf.IndexOf((byte)C.End, 1);
            if (end < 0) break; // trame incomplète
            var raw = _buf.GetRange(0, end + 1).ToArray();
            _buf.RemoveRange(0, end + 1);
            var frame = MdrFramer.Unpack(raw);
            if (frame != null) frames.Add(frame);
        }
        return frames;
    }
}
