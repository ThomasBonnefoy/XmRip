// App macOS du banc de test XmRip : une fenêtre de commandes pour le casque Sony.
//
// Le moteur reste le code Kotlin de l'app Android (`MdrSession`, module :desktop) : cette app compile le pont
// RFCOMM (../pc-bridge/Bridge.swift) dans son propre processus, lance le serveur Kotlin `:desktop:runWeb` en arrière-plan
// et lui parle en HTTP local. Elle ne réimplémente pas le protocole : ce qu'elle essaie, c'est le code qu'Android utilise.
import AppKit
import IOBluetooth
import SwiftUI

private let apiPort = 8765
private let bridgePort: UInt16 = 9876

struct HeadsetState: Decodable {
    let model: String?
    let firmware: String?
    let battery: Int
    let charging: String
    let ncKind: String?
    let ncLabel: String?
    let ambient: Int?
    let autoAmbient: Bool?
    let volume: Int?
    /// Code couleur annoncé par le casque, et la photo qui lui correspond (black, silver, blue, pink, olive, sand).
    let color: Int?
    let look: String?
}

/// Photos du casque copiées dans l'app par build.sh (les mêmes que l'app Android), chargées une fois chacune.
@MainActor
enum HeadsetPhoto {
    private static var cache: [String: NSImage] = [:]

    static func image(_ look: String) -> NSImage? {
        if let image = cache[look] { return image }
        guard let url = Bundle.main.url(forResource: "headphones_\(look)", withExtension: "webp"),
              let image = NSImage(contentsOf: url) else { return nil }
        cache[look] = image
        return image
    }
}

struct SettingsInfo: Decodable {
    let pauseWhenRemoved: Bool?
    let speakToChat: Bool?
    let dsee: Bool?
    let bgm: Bool?
    let bgmRoom: Int?
    let cinema: Bool?
    let autoPowerOff: Int?
    let ncButton: Int?
    let chatSensitivity: Int?
    let chatTimeout: Int?
    let connectionPriority: Int?
    let voiceGuidance: Bool?
    let voiceGuidanceVolume: Int?
}

struct EqInfo: Decodable {
    let label: String
    let preset: Int
    let bands: [Int]
}

struct DeviceInfo: Decodable, Identifiable {
    let address: String
    let name: String
    let connected: Bool
    let linkStatus: Int
    let playback: Bool
    var id: String { address }
}

struct DevicesInfo: Decodable {
    let pairingMode: Bool?
    let list: [DeviceInfo]
}

struct ApiReply: Decodable {
    let ok: Bool
    let error: String?
    let output: String
    let state: HeadsetState?
    let settings: SettingsInfo?
    let eq: EqInfo?
    let devices: DevicesInfo?
}

enum Phase: Equatable {
    case starting(String)
    case ready
    case failed(String)
}

@MainActor
final class Model: ObservableObject {
    @Published var phase: Phase = .starting("Démarrage…")
    @Published var state: HeadsetState?
    @Published var busy = false
    @Published var notice = ""
    @Published var settings: SettingsInfo?
    @Published var eq: EqInfo?
    @Published var devices: DevicesInfo?
    @Published var eqDraft = [Double](repeating: 0, count: 10)
    @Published var ambientLevel = 10.0
    @Published var volume = 10.0
    @Published var logLines: [String] = []
    /// Photo du casque : la dernière connue est gardée d'un lancement à l'autre, pour l'afficher avant la première réponse.
    @Published var look = UserDefaults.standard.string(forKey: "look") ?? "black"
    private var volumeBeforeMute = 10

    private var backend: Process?
    private var started = false

    func appendLog(_ line: String) {
        logLines.append(line)
        if logLines.count > 60 { logLines.removeFirst(logLines.count - 60) }
    }

    func start() {
        guard !started else { return }
        started = true
        phase = .starting("Démarrage du pont Bluetooth…")
        logSink = { line in DispatchQueue.main.async { MainActor.assumeIsolated { Model.shared?.appendLog(line) } } }
        Model.shared = self
        // IOBluetooth veut la run loop principale : on laisse l'interface s'afficher d'abord.
        DispatchQueue.main.async { MainActor.assumeIsolated { self.startBridge() } }
    }

    static var shared: Model?  // référence forte : l'arrêt du moteur doit survivre à la fermeture de la fenêtre

    private func startBridge() {
        signal(SIGPIPE, SIG_IGN)
        do {
            let device = try resolveDevice(nil)
            appendLog("Casque : \(device.name ?? "?") (\(fmtAddress(device)))")
            try startServer(device: device, port: bridgePort, trace: false)
        } catch {
            phase = .failed(error.localizedDescription)
            return
        }
        Task { await startBackend() }
    }

    private func ping() async -> Bool {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(apiPort)/ping")!)
        request.timeoutInterval = 1.5
        return (try? await URLSession.shared.data(for: request)).map { String(decoding: $0.0, as: UTF8.self) == "ok" } ?? false
    }

    private func startBackend() async {
        phase = .starting("Démarrage du moteur Kotlin (Gradle, jusqu'à une minute la première fois)…")
        if await !ping() {
            guard let root = Bundle.main.url(forResource: "root", withExtension: "txt")
                .flatMap({ try? String(contentsOf: $0, encoding: .utf8) })?.trimmingCharacters(in: .whitespacesAndNewlines),
                !root.isEmpty
            else {
                phase = .failed("root.txt manquant dans l'app : recompile avec tools/mac-app/build.sh")
                return
            }
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/bin/sh")
            process.currentDirectoryURL = URL(fileURLWithPath: root)
            process.arguments = ["-c", ". tools/env.sh; exec sh ./gradlew -Dorg.gradle.configureondemand=true :desktop:runWeb --console=plain -q --args='--port \(apiPort)'"]
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            pipe.fileHandleForReading.readabilityHandler = { handle in
                let text = String(decoding: handle.availableData, as: UTF8.self)
                for line in text.split(separator: "\n") {
                    DispatchQueue.main.async { MainActor.assumeIsolated { Model.shared?.appendLog(String(line)) } }
                }
            }
            do { try process.run() } catch {
                phase = .failed("Impossible de lancer Gradle : \(error.localizedDescription)")
                return
            }
            backend = process
            for _ in 0..<240 {
                if await ping() { break }
                if !process.isRunning {
                    phase = .failed("Le moteur Kotlin s'est arrêté (voir le journal ci-dessous).")
                    return
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            guard await ping() else {
                phase = .failed("Le moteur Kotlin ne répond pas.")
                return
            }
        }
        phase = .ready
        await send("battery eq settings", note: nil)
    }

    /// Envoie des commandes au moteur ; met à jour l'état du casque avec la réponse (état APRÈS les commandes).
    /// `note` : message affiché en bas (nil = rien, "" = la dernière ligne de sortie).
    func send(_ command: String, note: String? = "") async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(apiPort)/api")!)
        request.httpMethod = "POST"
        request.httpBody = Data(command.utf8)
        request.timeoutInterval = 90
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            let reply = try JSONDecoder().decode(ApiReply.self, from: data)
            if let s = reply.state {
                state = s
                if let l = s.look, l != look {
                    look = l
                    UserDefaults.standard.set(l, forKey: "look")
                }
                // Hors ambiant, le niveau annoncé vaut 0 : le garder ferait repartir « ambient 0 » (plage 1 à 20).
                if s.ncKind == "ambient", let a = s.ambient, a >= 1 { ambientLevel = Double(a) }
                if let v = s.volume {
                    volume = Double(v)
                    if v > 0 { volumeBeforeMute = v }
                }
            }
            if let v = reply.settings { settings = v }
            if let v = reply.eq {
                eq = v
                if v.bands.count == 10 { eqDraft = v.bands.map(Double.init) }
            }
            if let v = reply.devices { devices = v }
            if !reply.ok {
                notice = "Échec : \(reply.error ?? "erreur inconnue")"
                return
            }
            if let note {
                notice = note.isEmpty ? (reply.output.split(separator: "\n").last.map(String.init) ?? "OK") : note
            }
        } catch {
            notice = "Échec : \(error.localizedDescription)"
        }
    }

    /// Coupe le son (volume 0) ou le rétablit au dernier volume non nul.
    func toggleMute() async {
        if Int(volume) > 0 {
            volumeBeforeMute = Int(volume)
            await send("volume 0", note: "Son coupé")
        } else {
            await send("volume \(volumeBeforeMute)", note: "Volume \(volumeBeforeMute)")
        }
    }

    func stop() {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(apiPort)/quit")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 2
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, _, _ in done.signal() }.resume()
        _ = done.wait(timeout: .now() + 2)
        backend?.terminate()
    }
}

struct ContentView: View {
    @StateObject private var model = Model()

    var body: some View {
        Group {
            switch model.phase {
            case .starting(let message):
                VStack(spacing: 14) {
                    ProgressView()
                    Text(message).multilineTextAlignment(.center).foregroundStyle(.secondary)
                    LogView(lines: model.logLines)
                }
            case .failed(let message):
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle").font(.largeTitle).foregroundStyle(.orange)
                    Text(message).multilineTextAlignment(.center)
                    LogView(lines: model.logLines)
                }
            case .ready:
                Controls(model: model)
            }
        }
        .padding(20)
        .frame(minWidth: 520, minHeight: 620)
        .onAppear { model.start() }
    }
}

struct LogView: View {
    let lines: [String]
    var body: some View {
        ScrollView {
            Text(lines.suffix(15).joined(separator: "\n"))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
        .frame(maxHeight: 140)
    }
}

enum Tab: String, CaseIterable {
    case home = "Accueil", sound = "Son", devices = "Appareils", settings = "Réglages"
}

/// Un choix parmi plusieurs (libellé, valeur) ; `selection` nil = valeur pas encore lue.
struct Seg: View {
    let options: [(String, Int)]
    let selection: Int?
    let onSelect: (Int) -> Void
    var body: some View {
        Picker("", selection: Binding(get: { selection ?? Int.min }, set: { onSelect($0) })) {
            ForEach(options, id: \.1) { Text($0.0).tag($0.1) }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .disabled(selection == nil)
    }
}

struct ToggleRow: View {
    let title: String
    let subtitle: String?
    let value: Bool?
    let onChange: (Bool) -> Void
    var body: some View {
        Toggle(isOn: Binding(get: { value ?? false }, set: onChange)) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .toggleStyle(.switch)
        .disabled(value == nil)
    }
}

struct Controls: View {
    @ObservedObject var model: Model
    @State private var tab: Tab = .home
    @State private var confirmPowerOff = false
    @State private var pendingUnpair: DeviceInfo?

    private let bandNames = ["31", "63", "125", "250", "500", "1 k", "2 k", "4 k", "8 k", "16 k"]
    private let presets: [(String, String, Int)] = [
        ("Neutre", "off", 0x00), ("Heavy", "heavy", 0x30), ("Clear", "clear", 0x31),
        ("Hard", "hard", 0x32), ("Soft", "soft", 0x33), ("Perso", "custom", 0xA0),
    ]
    private let selfAddress = IOBluetoothHostController.default()?.addressAsString()?
        .replacingOccurrences(of: "-", with: ":").uppercased()

    private func run(_ command: String, note: String? = "") { Task { await model.send(command, note: note) } }
    private func onOff(_ on: Bool) -> String { on ? "on" : "off" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    switch tab {
                    case .home: home
                    case .sound: sound
                    case .devices: devicesTab
                    case .settings: settingsTab
                    }
                }
                .padding(.vertical, 2)
                .disabled(model.busy)
            }
            HStack {
                if model.busy { ProgressView().controlSize(.small) }
                Text(model.notice).foregroundStyle(.secondary).lineLimit(2)
                Spacer()
            }
        }
        .onChange(of: tab) { newTab in
            if newTab == .devices && model.devices == nil { run("devices", note: nil) }
        }
    }

    // MARK: Accueil

    private var ncBinding: Binding<String> {
        Binding(
            get: { model.state?.ncKind ?? "off" },
            set: { kind in run(kind == "ambient" ? "ambient \(Int(model.ambientLevel))" : kind) }
        )
    }

    private var home: some View {
        Group {
            HeadsetHero(look: model.look, state: model.state)
            GroupBox("Contrôle du bruit") {
                VStack(alignment: .leading, spacing: 8) {
                    Picker("", selection: ncBinding) {
                        Text("Désactivé").tag("off")
                        Text("Réduction").tag("nc")
                        Text("Ambiant").tag("ambient")
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    if model.state?.ncKind == "ambient" {
                        ToggleRow(title: "Automatique", subtitle: "Le casque règle le niveau selon le bruit autour de toi",
                                  value: model.state?.autoAmbient) { run("auto \(onOff($0))") }
                        HStack {
                            Text("Niveau").foregroundStyle(.secondary)
                            Slider(value: $model.ambientLevel, in: 1...20, step: 1) { editing in
                                if !editing { run("ambient \(Int(model.ambientLevel))") }
                            }
                            .disabled(model.state?.autoAmbient == true)
                            Text("\(Int(model.ambientLevel))").monospacedDigit().frame(width: 24, alignment: .trailing)
                        }
                    }
                }
                .padding(4)
            }
            GroupBox("Volume du casque") {
                HStack {
                    Slider(value: $model.volume, in: 0...30, step: 1) { editing in
                        if !editing { run("volume \(Int(model.volume))") }
                    }
                    Text("\(Int(model.volume))").monospacedDigit().frame(width: 24, alignment: .trailing)
                    Button(Int(model.volume) == 0 ? "Rétablir" : "Muet") { Task { await model.toggleMute() } }
                }
                .padding(4)
            }
            GroupBox("Fonctions") {
                VStack(alignment: .leading, spacing: 10) {
                    ToggleRow(title: "Speak-to-Chat", subtitle: "La musique se met en pause quand tu parles",
                              value: model.settings?.speakToChat) { run("chat \(onOff($0))") }
                    ToggleRow(title: "DSEE Extreme", subtitle: "Recrée les aigus perdus par la compression des fichiers",
                              value: model.settings?.dsee) { run("dsee \(onOff($0))") }
                    ToggleRow(title: "Pause au retrait", subtitle: "La lecture reprend quand tu remets le casque",
                              value: model.settings?.pauseWhenRemoved) { run("wear \(onOff($0))") }
                }
                .padding(4)
            }
        }
    }

    // MARK: Son

    private var listeningMode: Int? {
        guard let s = model.settings, s.bgm != nil || s.cinema != nil else { return nil }
        return s.bgm == true ? 1 : (s.cinema == true ? 2 : 0)
    }

    private var sound: some View {
        Group {
            GroupBox("Égaliseur") {
                VStack(alignment: .leading, spacing: 10) {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
                        ForEach(presets, id: \.1) { name, id, code in
                            let current = model.eq?.preset == code
                            Button { run("eq-preset \(id)") } label: { Text(name).frame(maxWidth: .infinity) }
                                .buttonStyle(.bordered)
                                .tint(current ? .accentColor : nil)
                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(current ? Color.accentColor : .clear, lineWidth: 2))
                        }
                    }
                    let custom = model.eq?.preset == 0xA0
                    Text(custom ? "Fais glisser une bande : la courbe part au relâché."
                                : "Courbe du préréglage « \(model.eq?.label ?? "?") ». Choisis « Perso » pour la modifier.")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(0..<10, id: \.self) { i in
                        HStack {
                            Text(bandNames[i]).font(.caption).monospacedDigit().frame(width: 34, alignment: .trailing)
                            Slider(value: $model.eqDraft[i], in: -6...6, step: 1) { editing in
                                if !editing { run("eq-bands " + model.eqDraft.map { String(Int($0)) }.joined(separator: " ")) }
                            }
                            .disabled(!custom)
                            Text(String(format: "%+d", Int(model.eqDraft[i]))).font(.caption).monospacedDigit()
                                .frame(width: 26, alignment: .trailing)
                        }
                    }
                    HStack {
                        Button("Remettre à plat") { run("eq-bands 0 0 0 0 0 0 0 0 0 0") }.disabled(!custom)
                        Button("Relire") { run("eq", note: nil) }
                    }
                }
                .padding(4)
            }
            GroupBox("Mode d'écoute") {
                VStack(alignment: .leading, spacing: 8) {
                    Seg(options: [("Standard", 0), ("Musique de fond", 1), ("Cinéma", 2)], selection: listeningMode) { mode in
                        let names = ["standard", "bgm", "cinema"]
                        run("listen \(names[mode]) \(model.settings?.bgmRoom ?? 0)")
                    }
                    if listeningMode == 1 {
                        Seg(options: [("Chambre", 0), ("Salon", 1), ("Café", 2)], selection: model.settings?.bgmRoom) { room in
                            run("listen bgm \(room)")
                        }
                    }
                    Text(["Rendu habituel du casque. L'égaliseur s'applique.",
                          "La musique semble venir d'enceintes dans la pièce, en arrière-plan.",
                          "Son spatialisé pour les films et les séries."][listeningMode ?? 0])
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(4)
            }
            GroupBox("Qualité") {
                VStack(alignment: .leading, spacing: 10) {
                    ToggleRow(title: "DSEE Extreme", subtitle: "Recrée les aigus perdus par la compression des fichiers",
                              value: model.settings?.dsee) { run("dsee \(onOff($0))") }
                    Text("Priorité de la liaison Bluetooth").foregroundStyle(.secondary)
                    Seg(options: [("Qualité du son", 0), ("Connexion stable", 1)], selection: model.settings?.connectionPriority) { value in
                        run("priority \(value == 1 ? "connection" : "quality")")
                    }
                }
                .padding(4)
            }
        }
    }

    // MARK: Appareils

    private var devicesTab: some View {
        Group {
            GroupBox("Mode appairage") {
                VStack(alignment: .leading, spacing: 6) {
                    ToggleRow(title: "Casque visible", subtitle: "Appaire un nouvel appareil depuis ses réglages Bluetooth",
                              value: model.devices?.pairingMode) { run("pairing \(onOff($0)) devices", note: nil) }
                }
                .padding(4)
            }
            GroupBox("Appareils du casque") {
                VStack(alignment: .leading, spacing: 10) {
                    if let list = model.devices?.list {
                        if list.isEmpty { Text("Aucun appareil appairé au casque.").foregroundStyle(.secondary) }
                        ForEach(list) { device in deviceRow(device) }
                        Text("Le n°1 est l'appareil connecté depuis le plus longtemps ; le casque n'en garde que deux à la fois.")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text("Liste pas encore lue.").foregroundStyle(.secondary)
                    }
                    Button("Actualiser") { run("devices", note: nil) }
                }
                .padding(4)
            }
        }
        .alert("Oublier cet appareil ?", isPresented: Binding(get: { pendingUnpair != nil }, set: { if !$0 { pendingUnpair = nil } })) {
            Button("Oublier", role: .destructive) {
                if let d = pendingUnpair { run("unpair \(d.address) devices", note: "Appareil oublié") }
                pendingUnpair = nil
            }
            Button("Annuler", role: .cancel) { pendingUnpair = nil }
        } message: {
            Text("\(pendingUnpair?.name ?? "") sera retiré du casque : il faudra l'appairer à nouveau.")
        }
    }

    private func deviceRow(_ d: DeviceInfo) -> some View {
        let isSelf = d.address == selfAddress
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(d.name.isEmpty ? "(sans nom)" : d.name).fontWeight(.medium)
                Text([d.connected ? "Connecté n°\(d.linkStatus)" : "Non connecté",
                      d.playback ? "Lecture" : nil, isSelf ? "Ce Mac" : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if isSelf {
                Text("liaison de ce banc").font(.caption).foregroundStyle(.tertiary)
            } else {
                if d.connected && !d.playback {
                    Button("Lire ici") { run("switch \(d.address) devices", note: "Lecture basculée") }
                }
                if d.connected {
                    Button("Déconnecter") { run("disconnect \(d.address) devices", note: "Appareil déconnecté") }
                } else {
                    Button("Connecter") { run("connect \(d.address) devices", note: "Connexion demandée") }
                }
                Button("Oublier…") { pendingUnpair = d }
            }
        }
    }

    // MARK: Réglages

    private var settingsTab: some View {
        let s = model.settings
        return Group {
            GroupBox("Conversation et port") {
                VStack(alignment: .leading, spacing: 10) {
                    ToggleRow(title: "Speak-to-Chat", subtitle: "La musique se met en pause quand tu parles",
                              value: s?.speakToChat) { run("chat \(onOff($0))") }
                    Text("Reprise de la musique après une conversation").foregroundStyle(.secondary)
                    Seg(options: [("Court", 0), ("Standard", 1), ("Long", 2), ("Jamais", 3)], selection: s?.chatTimeout) { run("chat-delay \($0)") }
                    if let sens = s?.chatSensitivity {
                        Text("Sensibilité de détection : \(["automatique", "haute", "basse"][min(sens, 2)])")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    ToggleRow(title: "Pause quand tu retires le casque", subtitle: "La lecture reprend quand tu le remets",
                              value: s?.pauseWhenRemoved) { run("wear \(onOff($0))") }
                }
                .padding(4)
            }
            GroupBox("Guidage vocal") {
                VStack(alignment: .leading, spacing: 10) {
                    ToggleRow(title: "Annonces vocales", subtitle: "« Bluetooth connecté », niveau de batterie, changement de mode…",
                              value: s?.voiceGuidance) { run("voice \(onOff($0))") }
                    Text("Volume des annonces").foregroundStyle(.secondary)
                    Seg(options: [("−2", -2), ("−1", -1), ("0", 0), ("+1", 1), ("+2", 2)], selection: s?.voiceGuidanceVolume) {
                        run("voice-volume \($0)")
                    }
                }
                .padding(4)
            }
            GroupBox("Bouton de bruit") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Modes que le bouton de l'oreillette gauche fait défiler.").font(.caption).foregroundStyle(.secondary)
                    Picker("", selection: Binding(get: { s?.ncButton ?? 0 }, set: { run("nc-button \($0)") })) {
                        Text("Les trois").tag(1)
                        Text("Bruit et ambiant").tag(2)
                        Text("Bruit et normal").tag(3)
                        Text("Ambiant et normal").tag(4)
                    }
                    .labelsHidden()
                    .disabled(s?.ncButton == nil)
                }
                .padding(4)
            }
            GroupBox("Alimentation") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Extinction automatique").foregroundStyle(.secondary)
                    Seg(options: [("Au retrait du casque", 0x10), ("Jamais", 0x11)], selection: s?.autoPowerOff) {
                        run("auto-off \($0 == 0x11 ? "never" : "removed")")
                    }
                    Button("Éteindre le casque…", role: .destructive) { confirmPowerOff = true }
                }
                .padding(4)
            }
            .alert("Éteindre le casque ?", isPresented: $confirmPowerOff) {
                Button("Éteindre", role: .destructive) { run("power-off", note: "Casque éteint") }
                Button("Annuler", role: .cancel) {}
            } message: {
                Text("Le casque s'éteint tout de suite et la musique s'arrête. Rallume-le avec son bouton d'alimentation.")
            }
            GroupBox("Outils") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Button("Actualiser tout") { run("battery eq settings devices", note: nil) }
                        Button("Copier le journal") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(model.logLines.joined(separator: "\n"), forType: .string)
                            model.notice = "Journal copié"
                        }
                    }
                    ScrollView {
                        Text(model.logLines.suffix(30).joined(separator: "\n"))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .frame(height: 90)
                }
                .padding(4)
            }
            GroupBox("À propos") {
                Text("XmRip Banc 0.1 · \(model.state?.model ?? "?") · firmware \(model.state?.firmware ?? "?")")
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(4)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Group {
                if let image = HeadsetPhoto.image(model.look) {
                    Image(nsImage: image).resizable().interpolation(.high).scaledToFit()
                } else {
                    Image(systemName: "headphones").resizable().scaledToFit().foregroundStyle(.tint)
                }
            }
            .frame(width: 30, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.state?.model ?? "Casque").font(.headline)
                if let s = model.state {
                    Text("Batterie \(s.battery) % · \(s.charging) · firmware \(s.firmware ?? "?")")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Button { run("battery eq settings", note: nil) } label: { Image(systemName: "arrow.clockwise") }
                .help("Actualiser")
        }
    }
}

/// En tête de l'accueil : la photo du casque à sa couleur dans l'anneau de batterie (270°, ouvert en bas, comme l'app
/// Android), avec une lueur qui suit le mode de bruit.
struct HeadsetHero: View {
    let look: String
    let state: HeadsetState?

    private var level: Int? { state?.battery }
    private var charging: Bool { state?.charging == "en charge" }

    /// Mêmes seuils que l'app Android ; violet fixe (son ACCENT) plutôt que la couleur d'accentuation du Mac, qui peut
    /// être rouge et ferait croire à une batterie faible.
    private var ringColor: Color {
        guard let level else { return .secondary }
        if charging { return .green }
        if level <= 15 { return .red }
        if level <= 30 { return .orange }
        return Color(red: 0x6F / 255, green: 0x5B / 255, blue: 1)
    }

    private var glow: Color {
        switch state?.ncKind {
        case "nc": return .indigo
        case "ambient": return .teal
        default: return .gray
        }
    }

    private var modeText: String {
        switch state?.ncKind {
        case "nc": return "Réduction de bruit"
        case "ambient": return state?.autoAmbient == true ? "Ambiant automatique" : "Ambiant · niveau \(state?.ambient ?? 0)"
        case "off": return "Contrôle du bruit désactivé"
        default: return state == nil ? "Lecture de l'état…" : "Mode inconnu"
        }
    }

    private var chargeText: String {
        switch state?.charging {
        case "en charge": return "En charge"
        case "chargée": return "Chargée"
        case nil: return "Batterie"
        default: return "Sur batterie"
        }
    }

    var body: some View {
        HStack(spacing: 22) {
            ZStack {
                Circle().fill(RadialGradient(colors: [glow.opacity(0.25), glow.opacity(0)], center: .center,
                                             startRadius: 0, endRadius: 92))
                Circle().trim(from: 0, to: 0.75)
                    .stroke(Color.secondary.opacity(0.2), style: StrokeStyle(lineWidth: 9, lineCap: .round))
                    .rotationEffect(.degrees(135))
                Circle().trim(from: 0, to: 0.75 * CGFloat(level ?? 0) / 100)
                    .stroke(ringColor, style: StrokeStyle(lineWidth: 9, lineCap: .round))
                    .rotationEffect(.degrees(135))
                Group {
                    if let image = HeadsetPhoto.image(look) {
                        Image(nsImage: image).resizable().interpolation(.high).scaledToFit()
                    } else {
                        Image(systemName: "headphones").resizable().scaledToFit().foregroundStyle(.secondary).padding(24)
                    }
                }
                .frame(height: 128)
                .opacity(state == nil ? 0.5 : 1)
            }
            .frame(width: 172, height: 172)
            .padding(6)
            .animation(.easeOut(duration: 0.5), value: level)
            .animation(.easeInOut(duration: 0.3), value: look)
            VStack(alignment: .leading, spacing: 8) {
                Text(level.map { "\($0) %" } ?? "– %")
                    .font(.system(size: 40, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Label(chargeText, systemImage: charging ? "bolt.fill" : "battery.75percent")
                    .foregroundStyle(.secondary)
                Text(modeText)
                    .font(.callout.weight(.medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(glow.opacity(0.16)))
            }
            Spacer(minLength: 0)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    /// Arrête le moteur Kotlin : la vue n'est plus là pour le faire une fois la fenêtre fermée.
    func applicationWillTerminate(_ notification: Notification) {
        MainActor.assumeIsolated { Model.shared?.stop() }
    }
}

@main
struct XmRipBancApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    var body: some Scene {
        WindowGroup("XmRip Banc") { ContentView() }
    }
}
