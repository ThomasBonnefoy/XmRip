// Pont RFCOMM macOS -> TCP local pour le banc de test PC de XmRip.
//
// Ouvre une connexion RFCOMM (UUID du service MDR de Sony) vers un casque appairé au Mac
// (IOBluetooth), et relaie les octets bruts vers/depuis une socket TCP sur localhost.
// Le module `desktop` (JVM) se connecte à ce port et réutilise MdrSession tel quel.
//
// Une connexion RFCOMM est ouverte par client TCP, ce qui reproduit le comportement
// de l'app (une session = un handshake complet). Équivalent macOS de rfcomm_bridge.py.
// Ce fichier est partagé par la ligne de commande (main.swift) et par l'app (tools/mac-app).
import Foundation
import IOBluetooth

// Doit rester identique à MdrConstants.SERVICE_UUID.
let mdrServiceUUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
let sonyName = try! NSRegularExpression(pattern: "WH-|WF-|XM|LINKBUDS|SONY", options: .caseInsensitive)

let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    return f
}()

/// Destination supplémentaire des traces (l'app y branche son journal) ; la ligne est toujours aussi imprimée.
nonisolated(unsafe) var logSink: ((String) -> Void)?

func log(_ msg: String) {
    let line = "\(clock.string(from: Date()))  \(msg)"
    print(line)
    logSink?(line)
}

struct BridgeError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func hex(_ bytes: [UInt8]) -> String {
    bytes.map { String(format: "%02x", $0) }.joined(separator: " ")
}

func fmtAddress(_ device: IOBluetoothDevice) -> String {
    (device.addressString ?? "??").replacingOccurrences(of: "-", with: ":").uppercased()
}

func uuidBytes(_ text: String) -> [UInt8] {
    let digits = Array(text.replacingOccurrences(of: "-", with: ""))
    return stride(from: 0, to: digits.count, by: 2).map { UInt8(String(digits[$0...$0 + 1]), radix: 16)! }
}

func pairedDevices() -> [IOBluetoothDevice] {
    (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
}

func resolveDevice(_ address: String?) throws -> IOBluetoothDevice {
    if let address {
        let normalized = address.replacingOccurrences(of: ":", with: "-")
        guard let dev = IOBluetoothDevice(addressString: normalized) else {
            throw BridgeError("Appareil \(address) introuvable (appairé au Mac ?)")
        }
        return dev
    }
    let candidates = pairedDevices().filter { dev in
        let name = dev.name ?? ""
        return sonyName.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
    }
    if candidates.isEmpty { throw BridgeError("Aucun casque Sony appairé trouvé. Utilise --list puis --address.") }
    if candidates.count > 1 {
        let names = candidates.map { "\($0.name ?? "?") (\(fmtAddress($0)))" }.joined(separator: ", ")
        throw BridgeError("Plusieurs candidats : \(names). Précise --address.")
    }
    return candidates[0]
}

/// Une session = un client TCP + un canal RFCOMM. Les appels IOBluetooth se font tous sur le thread principal
/// (celui de la run loop) ; le thread du client ne fait que lire la socket TCP.
final class Session: NSObject, IOBluetoothRFCOMMChannelDelegate {
    let device: IOBluetoothDevice
    let fd: Int32
    let trace: Bool
    var channel: IOBluetoothRFCOMMChannel?
    var closed = false

    init(device: IOBluetoothDevice, fd: Int32, trace: Bool) {
        self.device = device
        self.fd = fd
        self.trace = trace
    }

    func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        let bytes = Array(UnsafeBufferPointer(start: dataPointer.assumingMemoryBound(to: UInt8.self), count: dataLength))
        if trace { log("casque -> pc  \(hex(bytes))") }
        var sent = 0
        while sent < bytes.count {
            let n = bytes.withUnsafeBytes { send(fd, $0.baseAddress! + sent, bytes.count - sent, 0) }
            if n <= 0 { break }
            sent += n
        }
    }

    let closedSignal = DispatchSemaphore(value: 0)

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        if !closed { log("Casque: flux fermé") }
        shutdown(fd, SHUT_RDWR)
        closedSignal.signal()
    }

    let opened = DispatchSemaphore(value: 0)
    var openStatus: IOReturn = kIOReturnError

    func rfcommChannelOpenComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel!, status error: IOReturn) {
        openStatus = error
        opened.signal()
    }

    /// Thread principal pour le lancement, puis attente de rfcommChannelOpenComplete hors du thread principal.
    /// Renvoie nil si le canal est ouvert, sinon le message d'erreur.
    func open(channelID: BluetoothRFCOMMChannelID) -> String? {
        var ch: IOBluetoothRFCOMMChannel?
        var ret: IOReturn = kIOReturnError
        // Depuis la run loop principale (pas la file GCD), comme le font les callbacks IOBluetooth.
        let launched = DispatchSemaphore(value: 0)
        CFRunLoopPerformBlock(CFRunLoopGetMain(), CFRunLoopMode.defaultMode.rawValue) {
            if !self.device.isConnected() { _ = self.device.openConnection() }
            ret = self.device.openRFCOMMChannelAsync(&ch, withChannelID: channelID, delegate: self)
            launched.signal()
        }
        CFRunLoopWakeUp(CFRunLoopGetMain())
        launched.wait()
        guard ret == kIOReturnSuccess, let ch else {
            return String(format: "openRFCOMMChannelAsync a échoué (0x%08x)", ret)
        }
        channel = ch
        if opened.wait(timeout: .now() + 8) == .timedOut {
            DispatchQueue.main.sync { ch.setDelegate(nil); _ = ch.close() }
            channel = nil
            return "délai dépassé"
        }
        guard openStatus == kIOReturnSuccess else {
            return String(format: "ouverture refusée (0x%08x) : casque occupé par un autre appareil ?", openStatus)
        }
        return nil
    }

    /// Thread principal.
    func write(_ data: [UInt8]) {
        guard let channel else { return }
        let mtu = max(Int(channel.getMTU()), 1)
        var offset = 0
        while offset < data.count {
            let chunk = Array(data[offset..<min(offset + mtu, data.count)])
            var copy = chunk
            let ret = channel.writeSync(&copy, length: UInt16(chunk.count))
            if ret != kIOReturnSuccess {
                log(String(format: "Ecriture casque en erreur : 0x%08x", ret))
                return
            }
            offset += chunk.count
        }
    }

    /// Ferme le canal et attend la confirmation de fermeture (hors du thread principal) : rouvrir le canal juste après
    /// une fermeture non terminée fait expirer l'ouverture suivante.
    func close() {
        guard !closed else { return }
        closed = true
        guard let channel else { return }
        DispatchQueue.main.sync { _ = channel.close() }
        _ = closedSignal.wait(timeout: .now() + 3)
        self.channel = nil
    }
}

/// Coupe puis rétablit la liaison de base avec le casque. Après un arrêt brutal d'une session, le casque garde parfois
/// le canal MDR « occupé » jusqu'à la fin de la liaison : le canal ne s'ouvre alors jamais, sans erreur.
func resetBaseband(_ device: IOBluetoothDevice) {
    func onMain(_ work: () -> Void) { Thread.isMainThread ? work() : DispatchQueue.main.sync(execute: work) }
    onMain { _ = device.closeConnection() }
    Thread.sleep(forTimeInterval: 3)
    onMain { _ = device.openConnection() }
}

/// Interroge le SDP du casque pour trouver le canal RFCOMM du service MDR. Thread principal libre (run loop active).
final class SdpLookup: NSObject {
    let semaphore = DispatchSemaphore(value: 0)
    @objc func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        semaphore.signal()
    }
}

func mdrChannelID(_ device: IOBluetoothDevice) -> BluetoothRFCOMMChannelID? {
    var raw = uuidBytes(mdrServiceUUID)
    let uuid = IOBluetoothSDPUUID(bytes: &raw, length: raw.count)
    func lookup() -> BluetoothRFCOMMChannelID? {
        guard let record = device.getServiceRecord(for: uuid) else { return nil }
        var id: BluetoothRFCOMMChannelID = 0
        return record.getRFCOMMChannelID(&id) == kIOReturnSuccess ? id : nil
    }
    // Comme le UNCACHED de la version Windows : on rafraîchit d'abord, le cache ne sert qu'en secours.
    let sdp = SdpLookup()
    var started = false
    DispatchQueue.main.sync { started = device.performSDPQuery(sdp) == kIOReturnSuccess }
    if started { _ = sdp.semaphore.wait(timeout: .now() + 15) } 
    var id: BluetoothRFCOMMChannelID?
    DispatchQueue.main.sync { id = lookup() }
    return id
}

/// Un seul client à la fois : le casque n'accepte qu'une session MDR, et des tentatives qui s'empilent
/// (un client qui réessaie pendant que la précédente ouvre encore le canal) se gênent mutuellement.
let clientLock = NSLock()

func handleClient(device: IOBluetoothDevice, fd: Int32, trace: Bool) {
    clientLock.lock()
    defer { clientLock.unlock() }
    runClient(device: device, fd: fd, trace: trace)
}

func runClient(device: IOBluetoothDevice, fd: Int32, trace: Bool) {
    log("Client TCP connecté, ouverture RFCOMM vers \(device.name ?? "?")...")
    guard let channelID = mdrChannelID(device) else {
        log("Echec RFCOMM : service MDR absent (casque éteint, ou occupé par un autre appareil ?)")
        close(fd)
        return
    }
    var session = Session(device: device, fd: fd, trace: trace)
    if let failure = session.open(channelID: channelID) {
        log("Echec RFCOMM (\(failure)), nouvelle tentative après réinitialisation de la liaison...")
        resetBaseband(device)
        session = Session(device: device, fd: fd, trace: trace)
        if let failure = session.open(channelID: channelID) {
            log("Echec RFCOMM : \(failure) (casque éteint, ou occupé par un autre appareil ?)")
            close(fd)
            return
        }
    }
    log("RFCOMM établi.")
    var buf = [UInt8](repeating: 0, count: 1024)
    while true {
        let n = recv(fd, &buf, buf.count, 0)
        if n <= 0 { break }
        let data = Array(buf[0..<n])
        if trace { log("pc -> casque  \(hex(data))") }
        DispatchQueue.main.sync { session.write(data) }
    }
    session.close()
    close(fd)
    log("Session terminée.")
}

/// Ouvre le port TCP local puis accepte les clients sur un thread à part. Lève une erreur si le port est pris.
func startServer(device: IOBluetoothDevice, port: UInt16, trace: Bool) throws {
    let server = socket(AF_INET, SOCK_STREAM, 0)
    var yes: Int32 = 1
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = port.bigEndian
    addr.sin_addr.s_addr = inet_addr("127.0.0.1")
    let bound = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(server, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
    }
    guard bound == 0, listen(server, 4) == 0 else {
        close(server)
        throw BridgeError("Port \(port) indisponible (un autre pont tourne déjà ?)")
    }
    log("Pont prêt sur 127.0.0.1:\(port)")
    Thread.detachNewThread {
        while true {
            let fd = accept(server, nil, nil)
            if fd < 0 { continue }
            Thread.detachNewThread { handleClient(device: device, fd: fd, trace: trace) }
        }
    }
}
