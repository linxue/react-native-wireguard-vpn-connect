declare module 'react-native-wireguard-vpn' {
  export interface WireGuardConfig {
    privateKey: string;
    publicKey: string;
    serverAddress: string;
    serverPort: number;
    allowedIPs: string[];
    dns?: string[];
    mtu?: number;
    presharedKey?: string;
  }

  export interface WireGuardStatus {
    isConnected: boolean;
    tunnelState: 'UP' | 'DOWN' | 'UNKNOWN' | 'ERROR';
    error?: string;
    vpnPermissionGranted?: boolean;
  }

  export const WireGuardVpnModule: {
    initialize(): Promise<void>;
    requestVpnPermission(): Promise<string>;
    connect(config: WireGuardConfig): Promise<void>;
    disconnect(): Promise<void>;
    getStatus(): Promise<WireGuardStatus>;
    isSupported(): Promise<boolean>;
  };
} 