import { useGameStore } from '../store/gameStore';
import { PacketHandler } from './PacketHandler';

export enum ConnectionState {
  DISCONNECTED = 0,
  CONNECTING = 1,
  CONNECTED = 2,
  AUTHENTICATED = 3,
}

export interface WebSocketConfig {
  url: string;
  reconnectInterval: number;
  maxReconnectAttempts: number;
  heartbeatInterval: number;
}

const defaultConfig: WebSocketConfig = {
  url: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
  reconnectInterval: 5000,
  maxReconnectAttempts: 10,
  heartbeatInterval: 30000,
};

export class WebSocketClient {
  private socket: WebSocket | null = null;
  private config: WebSocketConfig;
  private state: ConnectionState = ConnectionState.DISCONNECTED;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private packetHandler: PacketHandler;
  private messageQueue: ArrayBuffer[] = [];

  constructor(config: Partial<WebSocketConfig> = {}) {
    this.config = { ...defaultConfig, ...config };
    this.packetHandler = new PacketHandler(this);
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.state !== ConnectionState.DISCONNECTED) {
        reject(new Error('Already connected or connecting'));
        return;
      }

      this.state = ConnectionState.CONNECTING;
      console.log(`Connecting to ${this.config.url}...`);

      try {
        this.socket = new WebSocket(this.config.url);
        this.socket.binaryType = 'arraybuffer';

        this.socket.onopen = () => {
          console.log('WebSocket connected');
          this.state = ConnectionState.CONNECTED;
          this.reconnectAttempts = 0;
          useGameStore.getState().setConnected(true);
          this.startHeartbeat();
          this.flushMessageQueue();
          resolve();
        };

        this.socket.onclose = (event) => {
          console.log(`WebSocket closed: ${event.code} ${event.reason}`);
          this.handleDisconnect();
        };

        this.socket.onerror = (error) => {
          console.error('WebSocket error:', error);
          if (this.state === ConnectionState.CONNECTING) {
            reject(error);
          }
        };

        this.socket.onmessage = (event) => {
          this.handleMessage(event.data);
        };
      } catch (error) {
        this.state = ConnectionState.DISCONNECTED;
        reject(error);
      }
    });
  }

  disconnect(): void {
    this.stopHeartbeat();
    this.stopReconnect();
    if (this.socket) {
      this.socket.close(1000, 'Client disconnect');
      this.socket = null;
    }
    this.state = ConnectionState.DISCONNECTED;
    useGameStore.getState().setConnected(false);
  }

  send(data: ArrayBuffer): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(data);
    } else {
      this.messageQueue.push(data);
    }
  }

  sendPacket(opcode: number, payload: Uint8Array = new Uint8Array()): void {
    const packet = new ArrayBuffer(payload.length + 3);
    const view = new DataView(packet);
    view.setUint8(0, opcode);
    view.setUint16(1, payload.length, false);
    new Uint8Array(packet, 3).set(payload);
    this.send(packet);
  }

  private handleMessage(data: ArrayBuffer): void {
    this.packetHandler.handlePacket(data);
  }

  private handleDisconnect(): void {
    this.state = ConnectionState.DISCONNECTED;
    useGameStore.getState().setConnected(false);
    this.stopHeartbeat();
    this.attemptReconnect();
  }

  private attemptReconnect(): void {
    if (this.reconnectAttempts >= this.config.maxReconnectAttempts) {
      console.log('Max reconnect attempts reached');
      return;
    }

    this.reconnectAttempts++;
    console.log(`Reconnect attempt ${this.reconnectAttempts}/${this.config.maxReconnectAttempts}`);

    this.reconnectTimer = setTimeout(() => {
      this.connect().catch(() => {
        // Will trigger another reconnect via onclose
      });
    }, this.config.reconnectInterval);
  }

  private stopReconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private startHeartbeat(): void {
    this.heartbeatTimer = setInterval(() => {
      this.sendPacket(0x00); // Heartbeat packet
    }, this.config.heartbeatInterval);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private flushMessageQueue(): void {
    while (this.messageQueue.length > 0) {
      const message = this.messageQueue.shift();
      if (message) {
        this.send(message);
      }
    }
  }

  getState(): ConnectionState {
    return this.state;
  }

  isConnected(): boolean {
    return this.state >= ConnectionState.CONNECTED;
  }

  isAuthenticated(): boolean {
    return this.state === ConnectionState.AUTHENTICATED;
  }

  setAuthenticated(): void {
    this.state = ConnectionState.AUTHENTICATED;
  }
}

// Singleton instance
let clientInstance: WebSocketClient | null = null;

export const getWebSocketClient = (): WebSocketClient => {
  if (!clientInstance) {
    clientInstance = new WebSocketClient();
  }
  return clientInstance;
};
