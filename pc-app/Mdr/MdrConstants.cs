namespace XmRipPc.Mdr;

/// <summary>
/// Constantes du protocole MDR v2. Portées de app/.../protocol/MdrConstants.kt : en cas de doute sur
/// un octet, ce fichier Kotlin et reference/ font foi.
/// </summary>
internal static class C
{
    public const string ServiceUuid = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2";

    public const int Start = 0x3E, End = 0x3C, Escape = 0x3D, Esc3C = 0x2C, Esc3D = 0x2D, Esc3E = 0x2E;

    public const int SocketTimeoutMs = 3000;
    public const int HandshakeTimeoutMs = 12_000;
    public const int EqEchoWaitMs = 1500;
    public const int SettingReplyWaitMs = 1500;
    public const int SettingReadWaitMs = 4000;
    public const int SettingConfirmWaitMs = 1200;
    public const int PeripheralActionTimeoutMs = 25_000;

    public const int TypeAck = 0x01, TypeData = 0x0C, TypeDataNo2 = 0x0E;

    public const int ConnectGetProtocolInfo = 0x00, ConnectRetProtocolInfo = 0x01;
    public const int ConnectGetCapabilityInfo = 0x02, ConnectRetCapabilityInfo = 0x03;
    public const int ConnectGetDeviceInfo = 0x04, ConnectRetDeviceInfo = 0x05;
    public const int ConnectGetSupportFunction = 0x06, ConnectRetSupportFunction = 0x07;

    public const int PowerGetStatus = 0x22, PowerRetStatus = 0x23, PowerSetStatus = 0x24, PowerNtfyStatus = 0x25;
    public const int PowerGetParam = 0x26, PowerRetParam = 0x27, PowerSetParam = 0x28, PowerNtfyParam = 0x29;
    public const int NcAsmGetParam = 0x66, NcAsmRetParam = 0x67, NcAsmSetParam = 0x68, NcAsmNtfyParam = 0x69;
    public const int EqGetParam = 0x56, EqRetParam = 0x57, EqSetParam = 0x58, EqNtfyParam = 0x59;
    public const int PlaySetStatus = 0xA4, PlayGetParam = 0xA6, PlayRetParam = 0xA7, PlaySetParam = 0xA8, PlayNtfyParam = 0xA9;
    public const int AudioGetParam = 0xE6, AudioRetParam = 0xE7, AudioSetParam = 0xE8, AudioNtfyParam = 0xE9;
    public const int SystemGetParam = 0xF6, SystemRetParam = 0xF7, SystemSetParam = 0xF8, SystemNtfyParam = 0xF9;
    /// <summary>Journal d'événements du casque : LOG_SET_STATUS l'active, LOG_NTFY_PARAM porte les noms de gestes (texte).</summary>
    public const int LogSetStatus = 0xC4, LogNtfyParam = 0xC9;
    public const int SystemGetExtParam = 0xFA, SystemRetExtParam = 0xFB, SystemSetExtParam = 0xFC, SystemNtfyExtParam = 0xFD;

    /// <summary>v2 : ON = 0x00, OFF = 0x01 (inversé par rapport à NC/ASM).</summary>
    public const int V2On = 0x00, V2Off = 0x01, V2Enable = 0x00;

    public const int PlayTypePlayback = 0x01, PlayTypeMusicVolume = 0x20;
    public const int PlayCtrlPause = 0x01, PlayCtrlTrackUp = 0x02, PlayCtrlTrackDown = 0x03, PlayCtrlPlay = 0x07;
    public const int PlayVolumeMax = 30;

    public const int PowerTypePowerOff = 0x03, PowerOffUser = 0x01;
    public const int SystemTypePlaybackByWearing = 0x01, SystemTypeSmartTalking = 0x0C;
    public const int SystemTypeAssignable = 0x03, SystemTypeAssignableLimit = 0x0E;
    public const int AudioTypeUpscaling = 0x01, AudioTypeCinema = 0x04, AudioTypeBgm = 0x09;
    public const int UpscalingOff = 0x00, UpscalingAuto = 0x01;
    public const int PowerTypeAutoPowerOffWearing = 0x05;
    public const int RoomSmall = 0x00;

    public const int EqTypeXm6 = 0x04;
    public const int EqOff = 0x00, EqHeavy = 0x30, EqClear = 0x31, EqHard = 0x32, EqSoft = 0x33, EqCustom = 0xA0;
    public const int EqBandCount = 10, EqGainMin = -6, EqGainMax = 6, EqBandOffset = 6;

    public const int NcAsmTypeDualNa = 0x19, NcAsmTypeButton = 0x30;
    /// <summary>Fonction du bouton de bruit (NC_AMB_TOGGLE) : modes que le bouton fait défiler.</summary>
    public const int NcButtonAll = 0x01, NcButtonNcAsm = 0x02, NcButtonNcOff = 0x03, NcButtonAsmOff = 0x04;
    public const int NcAsmOff = 0, NcAsmOn = 1, NcAsmModeNc = 0, NcAsmModeAsm = 1, NcAsmAmbientNormal = 0;
    public const int NcAsmStatusChanged = 1, NcAsmSensitivityStandard = 0;
    public const int AmbientLevelMax = 20, AmbientLevelDefault = 20;

    public const int DeviceInfoModelName = 0x01, DeviceInfoFwVersion = 0x02, DeviceInfoSeriesAndColor = 0x03;
    public const int PowerBattery = 0x00;

    public const int PeriGetStatus = 0x32, PeriRetStatus = 0x33, PeriSetStatus = 0x34, PeriNtfyStatus = 0x35;
    public const int PeriGetParam = 0x36, PeriRetParam = 0x37, PeriNtfyParam = 0x39, PeriSetExtParam = 0x3C, PeriNtfyExtParam = 0x3D;
    public const int T2FuncPairingClassicBt = 0x30, T2FuncPairingCodClassicBt = 0x32, T2FuncPairingCodClassicLe = 0x33;
    public const int PeriTypePairingClassicBt = 0x00, PeriTypeSourceSwitch = 0x01, PeriTypePairingWithCod = 0x02;
    public const int BtModeNormal = 0x00, BtModeInquiryScan = 0x01;
    public const int ConnActionDisconnect = 0x00, ConnActionConnect = 0x01;
    public const int MacAddressLength = 17;
    public const int PeripheralInProgress = 0x02;
}
