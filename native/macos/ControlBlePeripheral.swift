import CoreBluetooth
import Foundation

// MARK: - Configuration

private var instance: ControlBlePeripheral?

private struct QueuedMessage {
    let data: Data
}

private func bleLog(_ message: String) {
    let ts = ISO8601DateFormatter().string(from: Date())
    fputs("[\(ts)] BLE: \(message)\n", stderr)
    fflush(stderr)
}

// MARK: - Main class

final class ControlBlePeripheral: NSObject, CBPeripheralManagerDelegate {
    private let queue = DispatchQueue(label: "control-ble-peripheral")
    private var manager: CBPeripheralManager!
    private var service: CBMutableService?

    private var redCommandChar: CBMutableCharacteristic?
    private var redStatusChar: CBMutableCharacteristic?
    private var blueCommandChar: CBMutableCharacteristic?
    private var blueStatusChar: CBMutableCharacteristic?

    private let serviceUuid: CBUUID
    private let redCommandUuid: CBUUID
    private let redStatusUuid: CBUUID
    private let blueCommandUuid: CBUUID
    private let blueStatusUuid: CBUUID

    // Track which central subscribed to which team's command characteristic
    private var redCentral: CBCentral?
    private var blueCentral: CBCentral?

    // Thread-safe incoming data queues
    private var redInbox: [Data] = []
    private var blueInbox: [Data] = []
    private let inboxLock = NSLock()

    private var _redConnected = false
    private var _blueConnected = false

    init(serviceUuid: String, redCommandUuid: String, redStatusUuid: String,
         blueCommandUuid: String, blueStatusUuid: String) {
        self.serviceUuid = CBUUID(string: serviceUuid)
        self.redCommandUuid = CBUUID(string: redCommandUuid)
        self.redStatusUuid = CBUUID(string: redStatusUuid)
        self.blueCommandUuid = CBUUID(string: blueCommandUuid)
        self.blueStatusUuid = CBUUID(string: blueStatusUuid)
        super.init()
        bleLog("Initializing peripheral manager...")
        bleLog("  Service UUID: \(serviceUuid)")
        bleLog("  Red Command: \(redCommandUuid)")
        bleLog("  Red Status:  \(redStatusUuid)")
        bleLog("  Blue Command: \(blueCommandUuid)")
        bleLog("  Blue Status:  \(blueStatusUuid)")
        manager = CBPeripheralManager(delegate: self, queue: queue)
    }

    func stop() {
        queue.async {
            bleLog("Stopping peripheral...")
            self.manager.stopAdvertising()
            if let svc = self.service {
                self.manager.remove(svc)
            }
            self.cleanup()
            bleLog("Peripheral stopped.")
        }
    }

    private func cleanup() {
        redCentral = nil
        blueCentral = nil
        _redConnected = false
        _blueConnected = false
        inboxLock.lock()
        redInbox.removeAll()
        blueInbox.removeAll()
        inboxLock.unlock()
    }

    func isRedConnected() -> Bool { return _redConnected }
    func isBlueConnected() -> Bool { return _blueConnected }

    func sendToRed(_ data: Data) -> Bool {
        guard _redConnected, let char = redCommandChar, let central = redCentral else { return false }
        return manager.updateValue(data, for: char, onSubscribedCentrals: [central])
    }

    func sendToBlue(_ data: Data) -> Bool {
        guard _blueConnected, let char = blueCommandChar, let central = blueCentral else { return false }
        return manager.updateValue(data, for: char, onSubscribedCentrals: [central])
    }

    func recvFromRed(maxLen: Int) -> Data? {
        inboxLock.lock()
        defer { inboxLock.unlock() }
        guard !redInbox.isEmpty else { return nil }
        return redInbox.removeFirst()
    }

    func recvFromBlue(maxLen: Int) -> Data? {
        inboxLock.lock()
        defer { inboxLock.unlock() }
        guard !blueInbox.isEmpty else { return nil }
        return blueInbox.removeFirst()
    }

    // MARK: - Setup

    private func setupService() {
        bleLog("Setting up GATT service...")
        let redCmd = CBMutableCharacteristic(
            type: redCommandUuid,
            properties: [.notify],
            value: nil,
            permissions: []
        )
        let redStat = CBMutableCharacteristic(
            type: redStatusUuid,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let blueCmd = CBMutableCharacteristic(
            type: blueCommandUuid,
            properties: [.notify],
            value: nil,
            permissions: []
        )
        let blueStat = CBMutableCharacteristic(
            type: blueStatusUuid,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )

        redCommandChar = redCmd
        redStatusChar = redStat
        blueCommandChar = blueCmd
        blueStatusChar = blueStat

        let svc = CBMutableService(type: serviceUuid, primary: true)
        svc.characteristics = [redCmd, redStat, blueCmd, blueStat]
        service = svc

        manager.add(svc)
        bleLog("Service added to manager, waiting for callback...")
    }

    private func startAdvertising() {
        bleLog("Starting advertising...")
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUuid],
            CBAdvertisementDataLocalNameKey: "KotlinConf Control"
        ])
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let stateStr: String
        switch peripheral.state {
        case .poweredOn: stateStr = "poweredOn"
        case .poweredOff: stateStr = "poweredOff"
        case .unauthorized: stateStr = "unauthorized"
        case .unsupported: stateStr = "unsupported"
        case .resetting: stateStr = "resetting"
        case .unknown: stateStr = "unknown"
        @unknown default: stateStr = "unrecognized(\(peripheral.state.rawValue))"
        }
        bleLog("Peripheral manager state: \(stateStr)")

        if peripheral.state == .poweredOn {
            setupService()
        } else if peripheral.state == .unauthorized {
            bleLog("ERROR: Bluetooth unauthorized. Grant Bluetooth permission in System Settings > Privacy & Security > Bluetooth.")
        } else if peripheral.state == .poweredOff {
            bleLog("ERROR: Bluetooth is powered off. Turn on Bluetooth.")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            bleLog("ERROR adding service: \(error.localizedDescription)")
            return
        }
        bleLog("Service added successfully: \(service.uuid)")
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            bleLog("ERROR starting advertising: \(error.localizedDescription)")
        } else {
            bleLog("Advertising started successfully. Waiting for centrals to connect...")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        bleLog("Central \(central.identifier) subscribed to \(characteristic.uuid)")
        if characteristic.uuid == redCommandUuid {
            redCentral = central
            _redConnected = true
            bleLog("  -> Red team connected (via subscription)")
        } else if characteristic.uuid == blueCommandUuid {
            blueCentral = central
            _blueConnected = true
            bleLog("  -> Blue team connected (via subscription)")
        } else {
            bleLog("  -> Unknown characteristic subscription: \(characteristic.uuid)")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        bleLog("Central \(central.identifier) unsubscribed from \(characteristic.uuid)")
        if characteristic.uuid == redCommandUuid && central.identifier == redCentral?.identifier {
            redCentral = nil
            _redConnected = false
            bleLog("  -> Red team disconnected")
        } else if characteristic.uuid == blueCommandUuid && central.identifier == blueCentral?.identifier {
            blueCentral = nil
            _blueConnected = false
            bleLog("  -> Blue team disconnected")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        bleLog("Received \(requests.count) write request(s)")
        for request in requests {
            guard let data = request.value else {
                bleLog("  -> Write with no data from \(request.central.identifier), rejecting")
                manager.respond(to: request, withResult: .invalidAttributeValueLength)
                continue
            }

            bleLog("  -> \(data.count) bytes to characteristic \(request.characteristic.uuid) from \(request.central.identifier)")

            if request.characteristic.uuid == redStatusUuid {
                inboxLock.lock()
                redInbox.append(data)
                inboxLock.unlock()
                if redCentral == nil {
                    redCentral = request.central
                    _redConnected = true
                    bleLog("  -> Red team connected (via write fallback)")
                }
                manager.respond(to: request, withResult: .success)
            } else if request.characteristic.uuid == blueStatusUuid {
                inboxLock.lock()
                blueInbox.append(data)
                inboxLock.unlock()
                if blueCentral == nil {
                    blueCentral = request.central
                    _blueConnected = true
                    bleLog("  -> Blue team connected (via write fallback)")
                }
                manager.respond(to: request, withResult: .success)
            } else {
                bleLog("  -> Write to unknown characteristic \(request.characteristic.uuid), rejecting")
                manager.respond(to: request, withResult: .requestNotSupported)
            }
        }
    }
}

// MARK: - C API

@_cdecl("control_ble_start")
public func controlBleStart(
    _ serviceUuid: UnsafePointer<CChar>,
    _ redCmdUuid: UnsafePointer<CChar>,
    _ redStatusUuid: UnsafePointer<CChar>,
    _ blueCmdUuid: UnsafePointer<CChar>,
    _ blueStatusUuid: UnsafePointer<CChar>
) {
    let svc = String(cString: serviceUuid)
    let redCmd = String(cString: redCmdUuid)
    let redStat = String(cString: redStatusUuid)
    let blueCmd = String(cString: blueCmdUuid)
    let blueStat = String(cString: blueStatusUuid)

    bleLog("control_ble_start called")
    if instance != nil {
        bleLog("Stopping existing instance first...")
        instance?.stop()
        instance = nil
    }
    instance = ControlBlePeripheral(
        serviceUuid: svc, redCommandUuid: redCmd, redStatusUuid: redStat,
        blueCommandUuid: blueCmd, blueStatusUuid: blueStat
    )
}

@_cdecl("control_ble_stop")
public func controlBleStop() {
    bleLog("control_ble_stop called")
    instance?.stop()
    instance = nil
}

@_cdecl("control_ble_is_red_connected")
public func controlBleIsRedConnected() -> Bool {
    return instance?.isRedConnected() ?? false
}

@_cdecl("control_ble_is_blue_connected")
public func controlBleIsBlueConnected() -> Bool {
    return instance?.isBlueConnected() ?? false
}

@_cdecl("control_ble_send_red")
public func controlBleSendRed(_ data: UnsafePointer<UInt8>, _ length: Int32) -> Bool {
    guard let inst = instance else { return false }
    let d = Data(bytes: data, count: Int(length))
    return inst.sendToRed(d)
}

@_cdecl("control_ble_send_blue")
public func controlBleSendBlue(_ data: UnsafePointer<UInt8>, _ length: Int32) -> Bool {
    guard let inst = instance else { return false }
    let d = Data(bytes: data, count: Int(length))
    return inst.sendToBlue(d)
}

@_cdecl("control_ble_recv_red")
public func controlBleRecvRed(_ buffer: UnsafeMutablePointer<UInt8>, _ maxLen: Int32) -> Int32 {
    guard let inst = instance, let data = inst.recvFromRed(maxLen: Int(maxLen)) else { return 0 }
    let count = min(data.count, Int(maxLen))
    data.copyBytes(to: buffer, count: count)
    return Int32(count)
}

@_cdecl("control_ble_recv_blue")
public func controlBleRecvBlue(_ buffer: UnsafeMutablePointer<UInt8>, _ maxLen: Int32) -> Int32 {
    guard let inst = instance, let data = inst.recvFromBlue(maxLen: Int(maxLen)) else { return 0 }
    let count = min(data.count, Int(maxLen))
    data.copyBytes(to: buffer, count: count)
    return Int32(count)
}
