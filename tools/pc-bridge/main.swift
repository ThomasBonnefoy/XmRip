// Ligne de commande du pont RFCOMM macOS (voir Bridge.swift).
//
// Usage :
//     rfcomm_bridge --list
//     rfcomm_bridge                          # casque Sony détecté automatiquement
//     rfcomm_bridge --address AA:BB:CC:DD:EE:FF --port 9876 --trace [--reconnect]
import Foundation
import IOBluetooth

setvbuf(stdout, nil, _IOLBF, 0)

var address: String?
var port: UInt16 = 9876
var trace = false
var reconnect = false
var list = false
var args = CommandLine.arguments.dropFirst().makeIterator()
while let arg = args.next() {
    switch arg {
    case "--list": list = true
    case "--address": address = args.next()
    case "--port": port = UInt16(args.next() ?? "") ?? port
    case "--trace": trace = true
    case "--reconnect": reconnect = true
    default:
        print("Usage : rfcomm_bridge [--list] [--address AA:BB:CC:DD:EE:FF] [--port 9876] [--trace] [--reconnect]")
        exit(arg == "-h" || arg == "--help" ? 0 : 64)
    }
}

if list {
    for dev in pairedDevices() { print("\(fmtAddress(dev))  \(dev.name ?? "?")") }
    exit(0)
}

signal(SIGPIPE, SIG_IGN)
do {
    let device = try resolveDevice(address)
    log("Casque : \(device.name ?? "?") (\(fmtAddress(device)))")
    if reconnect {
        log("Réinitialisation de la liaison de base...")
        resetBaseband(device)
    }
    try startServer(device: device, port: port, trace: trace)
} catch {
    FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
    exit(1)
}
RunLoop.main.run()
