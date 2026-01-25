import React, { useState, useCallback } from 'react';
import { getWebSocketClient } from '../../network/WebSocketClient';
import { PacketHandler, ClientOpcode } from '../../network/PacketHandler';
import { useGameStore } from '../../store/gameStore';

export const LoginScreen: React.FC = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { isConnected } = useGameStore();

  const handleLogin = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();

    if (!username.trim() || !password.trim()) {
      setError('Please enter username and password');
      return;
    }

    if (username.length < 1 || username.length > 12) {
      setError('Username must be 1-12 characters');
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      const client = getWebSocketClient();

      // Connect if not already connected
      if (!client.isConnected()) {
        await client.connect();
      }

      // Send login packet
      const textEncoder = new TextEncoder();
      const usernameBytes = textEncoder.encode(username);
      const passwordBytes = textEncoder.encode(password);
      const payload = new Uint8Array(2 + usernameBytes.length + passwordBytes.length);

      payload[0] = usernameBytes.length;
      payload.set(usernameBytes, 1);
      payload[1 + usernameBytes.length] = passwordBytes.length;
      payload.set(passwordBytes, 2 + usernameBytes.length);

      client.sendPacket(ClientOpcode.LOGIN_REQUEST, payload);

      // Login response will be handled by PacketHandler
    } catch (err) {
      console.error('Login error:', err);
      setError('Failed to connect to server');
      setIsLoading(false);
    }
  }, [username, password]);

  const handleRegister = useCallback(() => {
    // Open registration modal or redirect
    window.open('/register', '_blank');
  }, []);

  return (
    <div className="login-screen">
      <div className="login-logo">
        <h1>ChainScape</h1>
        <p>OSRS with Solana GP</p>
      </div>

      <form className="login-form" onSubmit={handleLogin}>
        <input
          type="text"
          placeholder="Username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          maxLength={12}
          autoComplete="username"
          disabled={isLoading}
        />
        <input
          type="password"
          placeholder="Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          disabled={isLoading}
        />

        {error && <div className="login-error">{error}</div>}

        <button type="submit" disabled={isLoading || !isConnected}>
          {isLoading ? 'Logging in...' : isConnected ? 'Login' : 'Connecting...'}
        </button>

        <button type="button" onClick={handleRegister} className="register-btn">
          Create Account
        </button>
      </form>

      <div className="login-info">
        <p>Connect your Solana wallet to deposit/withdraw GP</p>
        <p>1 CSGP = 1 GP (no conversion fees)</p>
      </div>
    </div>
  );
};
