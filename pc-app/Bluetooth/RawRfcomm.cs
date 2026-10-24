using System.Runtime.InteropServices;

namespace XmRipPc.Bluetooth;

/// <summary>
/// Connexion RFCOMM directe par Winsock sur un canal connu. Évite la couche WinRT (StreamSocket), qui met 2 à 2,5 s
/// à ouvrir la même liaison alors que Winsock en met 0,5 à 1.
/// </summary>
internal static class RawRfcomm
{
    private const int AfBth = 32, SockStream = 1, BthProtoRfcomm = 3;
    private static readonly IntPtr InvalidSocket = new(-1);

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    private struct SockAddrBth
    {
        public ushort AddressFamily;
        public ulong BtAddr;
        public Guid ServiceClassId;
        public uint Port;
    }

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int WSAStartup(ushort version, byte[] data);

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern IntPtr socket(int af, int type, int protocol);

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int connect(IntPtr s, ref SockAddrBth name, int nameLen);

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int recv(IntPtr s, byte[] buf, int len, int flags);

    [DllImport("ws2_32.dll", SetLastError = true)]
    private static extern int send(IntPtr s, byte[] buf, int len, int flags);

    [DllImport("ws2_32.dll")]
    private static extern int closesocket(IntPtr s);

    static RawRfcomm() => WSAStartup(0x0202, new byte[512]);

    /// <summary>Ouvre la liaison (bloquant : à appeler hors du thread d'interface).</summary>
    public static Stream Connect(ulong address, int channel)
    {
        var s = socket(AfBth, SockStream, BthProtoRfcomm);
        if (s == InvalidSocket) throw new IOException($"Socket Bluetooth impossible (erreur {Marshal.GetLastWin32Error()})");
        var addr = new SockAddrBth { AddressFamily = AfBth, BtAddr = address, ServiceClassId = Guid.Empty, Port = (uint)channel };
        if (connect(s, ref addr, Marshal.SizeOf<SockAddrBth>()) != 0)
        {
            int error = Marshal.GetLastWin32Error();
            closesocket(s);
            throw new IOException($"Connexion RFCOMM refusée (erreur Winsock {error})");
        }
        return new RawStream(s);
    }

    private sealed class RawStream : Stream
    {
        private IntPtr _socket;

        public RawStream(IntPtr socket) => _socket = socket;

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();

        public override int Read(byte[] buffer, int offset, int count)
        {
            var temp = offset == 0 ? buffer : new byte[count];
            int n = recv(_socket, temp, count, 0);
            if (n < 0) return 0; // socket fermée ou liaison coupée : fin de flux
            if (!ReferenceEquals(temp, buffer)) Array.Copy(temp, 0, buffer, offset, n);
            return n;
        }

        public override void Write(byte[] buffer, int offset, int count)
        {
            var data = offset == 0 && count == buffer.Length ? buffer : buffer.AsSpan(offset, count).ToArray();
            int sent = 0;
            while (sent < count)
            {
                var chunk = sent == 0 ? data : data.AsSpan(sent).ToArray();
                int n = send(_socket, chunk, count - sent, 0);
                if (n < 0) throw new IOException($"Envoi RFCOMM impossible (erreur Winsock {Marshal.GetLastWin32Error()})");
                sent += n;
            }
        }

        protected override void Dispose(bool disposing)
        {
            var s = Interlocked.Exchange(ref _socket, IntPtr.Zero);
            if (s != IntPtr.Zero) closesocket(s);
            base.Dispose(disposing);
        }
    }
}
