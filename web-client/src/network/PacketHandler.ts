import { useGameStore } from '../store/gameStore';
import type { WebSocketClient } from './WebSocketClient';

// Server -> Client opcodes
export enum ServerOpcode {
  HEARTBEAT = 0x00,
  LOGIN_RESPONSE = 0x01,
  PLAYER_UPDATE = 0x02,
  INVENTORY_UPDATE = 0x03,
  CHAT_MESSAGE = 0x04,
  SYSTEM_MESSAGE = 0x05,
  DEPOSIT_ADDRESS_RESPONSE = 0x10,
  GP_UPDATE = 0x12,
  TRANSACTION_STATUS = 0x13,
  DEPOSIT_CREDITED = 0x14,
  WORLD_UPDATE = 0x20,
  NPC_UPDATE = 0x21,
  GROUND_ITEMS = 0x22,
  MAP_REGION = 0x23,
}

// Client -> Server opcodes
export enum ClientOpcode {
  HEARTBEAT = 0x00,
  LOGIN_REQUEST = 0x01,
  LOGOUT_REQUEST = 0x02,
  PLAYER_ACTION = 0x03,
  CHAT_MESSAGE = 0x04,
  DEPOSIT_ADDRESS_REQUEST = 0x10,
  WITHDRAW_REQUEST = 0x12,
  MOVEMENT = 0x20,
  INTERFACE_ACTION = 0x21,
}

export class PacketHandler {
  private client: WebSocketClient;
  private textDecoder = new TextDecoder();
  private textEncoder = new TextEncoder();

  constructor(client: WebSocketClient) {
    this.client = client;
  }

  handlePacket(data: ArrayBuffer): void {
    const view = new DataView(data);
    const opcode = view.getUint8(0);
    const length = view.getUint16(1, false);
    const payload = new Uint8Array(data, 3, length);

    switch (opcode) {
      case ServerOpcode.HEARTBEAT:
        // Server acknowledged heartbeat
        break;
      case ServerOpcode.LOGIN_RESPONSE:
        this.handleLoginResponse(payload);
        break;
      case ServerOpcode.PLAYER_UPDATE:
        this.handlePlayerUpdate(payload);
        break;
      case ServerOpcode.INVENTORY_UPDATE:
        this.handleInventoryUpdate(payload);
        break;
      case ServerOpcode.CHAT_MESSAGE:
        this.handleChatMessage(payload);
        break;
      case ServerOpcode.SYSTEM_MESSAGE:
        this.handleSystemMessage(payload);
        break;
      case ServerOpcode.DEPOSIT_ADDRESS_RESPONSE:
        this.handleDepositAddressResponse(payload);
        break;
      case ServerOpcode.GP_UPDATE:
        this.handleGPUpdate(payload);
        break;
      case ServerOpcode.TRANSACTION_STATUS:
        this.handleTransactionStatus(payload);
        break;
      case ServerOpcode.WORLD_UPDATE:
        this.handleWorldUpdate(payload);
        break;
      default:
        console.warn(`Unknown opcode: ${opcode}`);
    }
  }

  private handleLoginResponse(payload: Uint8Array): void {
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const success = view.getUint8(0) === 1;

    if (success) {
      this.client.setAuthenticated();
      useGameStore.getState().setLoggedIn(true);

      // Parse player data
      const usernameLength = view.getUint8(1);
      const username = this.textDecoder.decode(payload.slice(2, 2 + usernameLength));
      const rights = view.getUint8(2 + usernameLength);

      useGameStore.getState().setPlayer({
        username,
        rights,
        position: { x: 3222, y: 3218, z: 0 },
        stats: {},
        inventory: [],
        gpInInventory: 0,
        gpInBank: 0,
      });
    } else {
      const reasonLength = view.getUint8(1);
      const reason = this.textDecoder.decode(payload.slice(2, 2 + reasonLength));
      console.error('Login failed:', reason);
    }
  }

  private handlePlayerUpdate(payload: Uint8Array): void {
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const x = view.getInt32(0, false);
    const y = view.getInt32(4, false);
    const z = view.getUint8(8);

    useGameStore.getState().updatePlayer({
      position: { x, y, z },
    });
  }

  private handleInventoryUpdate(payload: Uint8Array): void {
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const itemCount = view.getUint16(0, false);
    const inventory = [];

    let offset = 2;
    for (let i = 0; i < itemCount; i++) {
      const slot = view.getUint8(offset);
      const id = view.getUint16(offset + 1, false);
      const amount = view.getInt32(offset + 3, false);
      inventory.push({ slot, id, amount });
      offset += 7;
    }

    useGameStore.getState().updatePlayer({ inventory });
  }

  private handleChatMessage(payload: Uint8Array): void {
    const message = this.textDecoder.decode(payload);
    console.log('Chat:', message);
    // Dispatch to chat UI
  }

  private handleSystemMessage(payload: Uint8Array): void {
    const message = this.textDecoder.decode(payload);
    console.log('System:', message);
    // Display system message
  }

  private handleDepositAddressResponse(payload: Uint8Array): void {
    const address = this.textDecoder.decode(payload);
    window.dispatchEvent(
      new CustomEvent('wallet:depositAddress', { detail: { address } })
    );
  }

  private handleGPUpdate(payload: Uint8Array): void {
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const gpInInventory = Number(view.getBigInt64(0, false));
    const gpInBank = Number(view.getBigInt64(8, false));

    useGameStore.getState().updatePlayer({ gpInInventory, gpInBank });
  }

  private handleTransactionStatus(payload: Uint8Array): void {
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const txIdLength = view.getUint8(0);
    const txId = this.textDecoder.decode(payload.slice(1, 1 + txIdLength));
    const status = view.getUint8(1 + txIdLength);

    const statusMap: Record<number, 'pending' | 'confirmed' | 'failed'> = {
      0: 'pending',
      1: 'confirmed',
      2: 'failed',
    };

    useGameStore.getState().updateTransaction(txId, {
      status: statusMap[status] || 'pending',
    });
  }

  private handleWorldUpdate(payload: Uint8Array): void {
    // World state update - player positions, NPCs, etc.
    // Dispatch to renderer
    window.dispatchEvent(
      new CustomEvent('world:update', { detail: { payload } })
    );
  }

  // Client -> Server packet builders
  sendLoginRequest(username: string, password: string): void {
    const usernameBytes = this.textEncoder.encode(username);
    const passwordBytes = this.textEncoder.encode(password);
    const payload = new Uint8Array(2 + usernameBytes.length + passwordBytes.length);

    payload[0] = usernameBytes.length;
    payload.set(usernameBytes, 1);
    payload[1 + usernameBytes.length] = passwordBytes.length;
    payload.set(passwordBytes, 2 + usernameBytes.length);

    this.client.sendPacket(ClientOpcode.LOGIN_REQUEST, payload);
  }

  sendDepositAddressRequest(): void {
    this.client.sendPacket(ClientOpcode.DEPOSIT_ADDRESS_REQUEST);
  }

  sendWithdrawRequest(amount: bigint, destinationAddress: string): void {
    const addressBytes = this.textEncoder.encode(destinationAddress);
    const payload = new Uint8Array(8 + 1 + addressBytes.length);
    const view = new DataView(payload.buffer);
    view.setBigInt64(0, amount, false);
    payload[8] = addressBytes.length;
    payload.set(addressBytes, 9);
    this.client.sendPacket(ClientOpcode.WITHDRAW_REQUEST, payload);
  }

  sendWithdrawRequestLegacy(amount: bigint): void {
    const payload = new Uint8Array(8);
    const view = new DataView(payload.buffer);
    view.setBigInt64(0, amount, false);
    this.client.sendPacket(ClientOpcode.WITHDRAW_REQUEST, payload);
  }

  sendMovement(x: number, y: number, running: boolean): void {
    const payload = new Uint8Array(9);
    const view = new DataView(payload.buffer);
    view.setInt32(0, x, false);
    view.setInt32(4, y, false);
    view.setUint8(8, running ? 1 : 0);
    this.client.sendPacket(ClientOpcode.MOVEMENT, payload);
  }

  sendChatMessage(message: string): void {
    const payload = this.textEncoder.encode(message);
    this.client.sendPacket(ClientOpcode.CHAT_MESSAGE, payload);
  }
}
