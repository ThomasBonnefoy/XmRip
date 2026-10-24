using System.Text.RegularExpressions;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;
using XmRipPc.Mdr;

namespace XmRipPc.Bluetooth;

/// <summary>Connexion RFCOMM au service MDR du casque, via la pile Bluetooth de Windows (casque appairé au PC).</summary>
internal sealed class HeadsetLink
{
    private static readonly Regex SonyName = new(@"WH-|WF-|XM|LINKBUDS|SONY", RegexOptions.IgnoreCase);
    private static readonly Guid MdrService = new(C.ServiceUuid);

    private ulong? _address;
    private int? _channel;

    public string? DeviceName { get; private set; }

    /// <summary>
    /// Ouvre une session MDR. Chemin rapide : Winsock direct sur le canal RFCOMM mémorisé (0,5 à 1 s). Sinon, ou si
    /// ce chemin échoue, WinRT (2 à 2,5 s), qui retrouve aussi le canal pour les fois suivantes.
    /// </summary>
    public async Task<MdrSession> OpenAsync(Action<string>? trace)
    {
        LoadMemory();
        if (_address is ulong address && _channel is int channel)
        {
            try
            {
                var stream = await Task.Run(() => RawRfcomm.Connect(address, channel));
                return new MdrSession(stream, stream) { Trace = trace };
            }
            catch (Exception e)
            {
                trace?.Invoke($"Winsock direct échoué ({e.Message}) : repli sur WinRT");
            }
        }
        return await OpenWithWinRtAsync(trace);
    }

    private async Task<MdrSession> OpenWithWinRtAsync(Action<string>? trace)
    {
        var device = await ResolveDeviceAsync();
        // Le cache évite la recherche de service (SDP) : 20 ms au lieu de près de 2 s. Repli sur une vraie
        // recherche s'il est vide, ou si la connexion avec le service en cache échoue.
        var service = await FindServiceAsync(device, BluetoothCacheMode.Cached);
        MdrSession session;
        try
        {
            session = await ConnectAsync(service, trace);
        }
        catch (Exception) when (_serviceFromCache)
        {
            service = await FindServiceAsync(device, BluetoothCacheMode.Uncached);
            session = await ConnectAsync(service, trace);
        }
        await LearnChannelAsync(service);
        return session;
    }

    /// <summary>Lit le canal RFCOMM dans les attributs SDP du service (ProtocolDescriptorList) et le mémorise.</summary>
    private async Task LearnChannelAsync(RfcommDeviceService service)
    {
        try
        {
            var attributes = await service.GetSdpRawAttributesAsync();
            if (!attributes.TryGetValue(0x0004, out var buffer)) return;
            var bytes = new byte[buffer.Length];
            Windows.Storage.Streams.DataReader.FromBuffer(buffer).ReadBytes(bytes);
            // ... 19 00 03 (UUID RFCOMM) 08 nn (canal sur un octet)
            for (int i = 0; i + 4 < bytes.Length; i++)
            {
                if (bytes[i] == 0x19 && bytes[i + 1] == 0x00 && bytes[i + 2] == 0x03 && bytes[i + 3] == 0x08)
                {
                    _channel = bytes[i + 4];
                    SaveMemory();
                    return;
                }
            }
        }
        catch { /* le prochain lancement passera encore par WinRT */ }
    }

    private bool _serviceFromCache;

    private async Task<RfcommDeviceService> FindServiceAsync(BluetoothDevice device, BluetoothCacheMode mode)
    {
        var result = await device.GetRfcommServicesForIdAsync(RfcommServiceId.FromUuid(MdrService), mode);
        _serviceFromCache = mode == BluetoothCacheMode.Cached;
        if (result.Services.Count > 0) return result.Services[0];
        if (mode == BluetoothCacheMode.Cached) return await FindServiceAsync(device, BluetoothCacheMode.Uncached);
        throw new IOException("Service MDR absent : casque éteint, hors de portée ou occupé par un autre appareil ?");
    }

    private static async Task<MdrSession> ConnectAsync(RfcommDeviceService service, Action<string>? trace)
    {
        var socket = new StreamSocket();
        try
        {
            await socket.ConnectAsync(service.ConnectionHostName, service.ConnectionServiceName,
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
        return new SocketSession(socket, trace).Session;
    }

    /// <summary>Adresse et canal du casque, mémorisés d'un lancement à l'autre (« adresse;canal »).</summary>
    private static readonly string MemoryFile =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "XmRipPc", "device.txt");

    private bool _memoryLoaded;

    private void LoadMemory()
    {
        if (_memoryLoaded) return;
        _memoryLoaded = true;
        try
        {
            var parts = File.ReadAllText(MemoryFile).Trim().Split(';');
            if (ulong.TryParse(parts[0], out var a)) _address = a;
            if (parts.Length > 1 && int.TryParse(parts[1], out var c)) _channel = c;
        }
        catch { /* première exécution */ }
    }

    private void SaveMemory()
    {
        if (_address is not ulong a) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(MemoryFile)!);
            File.WriteAllText(MemoryFile, _channel is int c ? $"{a};{c}" : a.ToString());
        }
        catch { /* mémoire facultative */ }
    }

    private async Task<BluetoothDevice> ResolveDeviceAsync()
    {
        LoadMemory();
        if (_address is ulong known)
        {
            var cached = await BluetoothDevice.FromBluetoothAddressAsync(known);
            if (cached != null)
            {
                DeviceName = cached.Name;
                return cached;
            }
        }

        var infos = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
        BluetoothDevice? found = null;
        foreach (var info in infos)
        {
            var dev = await BluetoothDevice.FromIdAsync(info.Id);
            if (dev != null && SonyName.IsMatch(dev.Name ?? ""))
            {
                found = dev;
                break;
            }
        }
        if (found == null)
            throw new IOException("Aucun casque Sony appairé à ce PC (Paramètres Windows, Bluetooth et appareils).");

        _address = found.BluetoothAddress;
        _channel = null; // autre appareil : le canal sera relu
        SaveMemory();
        DeviceName = found.Name;
        return found;
    }

    /// <summary>Lie la durée de vie de la socket WinRT à celle de la session.</summary>
    private sealed class SocketSession
    {
        public MdrSession Session { get; }

        public SocketSession(StreamSocket socket, Action<string>? trace)
        {
            var input = socket.InputStream.AsStreamForRead();
            var output = new SocketOutput(socket);
            Session = new MdrSession(input, output) { Trace = trace };
        }
    }

    /// <summary>Flux d'écriture qui ferme aussi la socket quand la session est libérée.</summary>
    private sealed class SocketOutput : Stream
    {
        private readonly StreamSocket _socket;
        private readonly Stream _inner;

        public SocketOutput(StreamSocket socket)
        {
            _socket = socket;
            _inner = socket.OutputStream.AsStreamForWrite();
        }

        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => _inner.Flush();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => _inner.Write(buffer, offset, count);

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                try { _inner.Dispose(); } catch { }
                try { _socket.Dispose(); } catch { }
            }
            base.Dispose(disposing);
        }
    }
}
