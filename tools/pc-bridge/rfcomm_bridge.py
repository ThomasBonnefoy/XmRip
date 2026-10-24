"""Pont RFCOMM Windows -> TCP local pour le banc de test PC de XmRip.

Ouvre une connexion RFCOMM (UUID du service MDR de Sony) vers un casque appairé
à Windows, et relaie les octets bruts vers/depuis une socket TCP sur localhost.
Le module `desktop` (JVM) se connecte à ce port et réutilise MdrSession tel quel.

Une connexion RFCOMM est ouverte par client TCP, ce qui reproduit le comportement
de l'app (une session = un handshake complet).

Usage :
    python rfcomm_bridge.py --list
    python rfcomm_bridge.py                      # casque Sony détecté automatiquement
    python rfcomm_bridge.py --address AA:BB:CC:DD:EE:FF --port 9876
"""
import argparse
import asyncio
import re
import sys
import time
import uuid

from winsdk.windows.devices.bluetooth import BluetoothCacheMode, BluetoothDevice
from winsdk.windows.devices.bluetooth.rfcomm import RfcommServiceId
from winsdk.windows.devices.enumeration import DeviceInformation
from winsdk.windows.networking.sockets import SocketProtectionLevel, StreamSocket
from winsdk.windows.storage.streams import DataReader, DataWriter, InputStreamOptions

# Doit rester identique à MdrConstants.SERVICE_UUID.
MDR_SERVICE_UUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
SONY_NAME = re.compile(r"WH-|WF-|XM|LINKBUDS|SONY", re.IGNORECASE)


sys.stdout.reconfigure(encoding="utf-8")


def log(msg: str) -> None:
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d}  {msg}", flush=True)


def fmt_address(value: int) -> str:
    return ":".join(f"{(value >> shift) & 0xFF:02X}" for shift in range(40, -8, -8))


def parse_address(text: str) -> int:
    return int(re.sub(r"[^0-9A-Fa-f]", "", text), 16)


async def paired_devices():
    selector = BluetoothDevice.get_device_selector_from_pairing_state(True)
    infos = await DeviceInformation.find_all_async(selector, [])
    devices = []
    for info in infos:
        dev = await BluetoothDevice.from_id_async(info.id)
        if dev is not None:
            devices.append(dev)
    return devices


async def resolve_device(address: str | None) -> BluetoothDevice:
    if address:
        dev = await BluetoothDevice.from_bluetooth_address_async(parse_address(address))
        if dev is None:
            raise SystemExit(f"Appareil {address} introuvable (appairé à Windows ?)")
        return dev
    candidates = []
    for _ in range(3):  # l'énumération WinRT renvoie parfois une liste vide juste après le démarrage
        candidates = [d for d in await paired_devices() if SONY_NAME.search(d.name or "")]
        if candidates:
            break
        await asyncio.sleep(1)
    if not candidates:
        raise SystemExit("Aucun casque Sony appairé trouvé. Utilise --list puis --address.")
    if len(candidates) > 1:
        names = ", ".join(f"{d.name} ({fmt_address(d.bluetooth_address)})" for d in candidates)
        raise SystemExit(f"Plusieurs candidats : {names}. Précise --address.")
    return candidates[0]


async def open_rfcomm(device: BluetoothDevice) -> StreamSocket:
    service_id = RfcommServiceId.from_uuid(uuid.UUID(MDR_SERVICE_UUID))
    result = await device.get_rfcomm_services_for_id_async(service_id, BluetoothCacheMode.UNCACHED)
    if result.services.size == 0:
        raise ConnectionError("Service MDR absent (casque éteint, ou occupé par un autre appareil ?)")
    service = result.services[0]
    sock = StreamSocket()
    await sock.connect_async(
        service.connection_host_name,
        service.connection_service_name,
        SocketProtectionLevel.BLUETOOTH_ENCRYPTION_ALLOW_NULL_AUTHENTICATION,
    )
    return sock


async def pump_headset_to_tcp(sock: StreamSocket, writer: asyncio.StreamWriter, trace: bool) -> None:
    reader = DataReader(sock.input_stream)
    reader.input_stream_options = InputStreamOptions.PARTIAL
    try:
        while True:
            loaded = await reader.load_async(1024)
            if loaded == 0:
                log("Casque: flux fermé")
                return
            buf = bytearray(loaded)
            reader.read_bytes(buf)
            if trace:
                log(f"casque -> pc  {bytes(buf).hex(' ')}")
            writer.write(bytes(buf))
            await writer.drain()
    except Exception as exc:  # noqa: BLE001
        log(f"Lecture casque en erreur : {exc!r}")


async def pump_tcp_to_headset(sock: StreamSocket, reader: asyncio.StreamReader, trace: bool) -> None:
    out = DataWriter(sock.output_stream)
    try:
        while True:
            data = await reader.read(1024)
            if not data:
                return
            if trace:
                log(f"pc -> casque  {data.hex(' ')}")
            out.write_bytes(data)
            await out.store_async()
            await out.flush_async()
    except Exception as exc:  # noqa: BLE001
        log(f"Ecriture casque en erreur : {exc!r}")


async def handle_client(
    device: BluetoothDevice, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, trace: bool
) -> None:
    log(f"Client TCP connecté, ouverture RFCOMM vers {device.name}...")
    try:
        sock = await open_rfcomm(device)
    except Exception as exc:  # noqa: BLE001 - toute erreur WinRT doit fermer le client proprement
        log(f"Echec RFCOMM : {exc}")
        writer.close()
        return
    log("RFCOMM établi.")
    tasks = [
        asyncio.ensure_future(pump_headset_to_tcp(sock, writer, trace)),
        asyncio.ensure_future(pump_tcp_to_headset(sock, reader, trace)),
    ]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        sock.close()
        writer.close()
        log("Session terminée.")


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="liste les appareils appairés et quitte")
    parser.add_argument("--address", help="adresse Bluetooth du casque (AA:BB:CC:DD:EE:FF)")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--trace", action="store_true", help="affiche les octets échangés")
    args = parser.parse_args()

    if args.list:
        for dev in await paired_devices():
            print(f"{fmt_address(dev.bluetooth_address)}  {dev.name}")
        return

    # Annuler une lecture WinRT en cours (fermeture de session) fait remonter un InvalidStateError
    # sans conséquence dans la boucle asyncio ; on ne pollue pas la sortie avec.
    def ignore_cancel_noise(loop, context):
        if not isinstance(context.get("exception"), asyncio.InvalidStateError):
            loop.default_exception_handler(context)

    asyncio.get_running_loop().set_exception_handler(ignore_cancel_noise)

    device = await resolve_device(args.address)
    log(f"Casque : {device.name} ({fmt_address(device.bluetooth_address)})")
    server = await asyncio.start_server(
        lambda r, w: handle_client(device, r, w, args.trace), "127.0.0.1", args.port
    )
    log(f"Pont prêt sur 127.0.0.1:{args.port} (Ctrl+C pour arrêter)")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
