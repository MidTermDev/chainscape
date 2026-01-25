import React, { useRef, useEffect } from 'react';
import { GameRenderer } from '../../engine/renderer/GameRenderer';
import { InputHandler } from '../../engine/input/InputHandler';
import { useGameStore } from '../../store/gameStore';

export const GameCanvas: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rendererRef = useRef<GameRenderer | null>(null);
  const inputHandlerRef = useRef<InputHandler | null>(null);
  const { player } = useGameStore();

  useEffect(() => {
    if (!canvasRef.current) return;

    // Initialize renderer
    rendererRef.current = new GameRenderer(canvasRef.current);
    rendererRef.current.initialize();

    // Initialize input handler
    inputHandlerRef.current = new InputHandler(canvasRef.current);

    // Start render loop
    const animate = () => {
      if (rendererRef.current) {
        rendererRef.current.render();
      }
      requestAnimationFrame(animate);
    };
    animate();

    // Handle resize
    const handleResize = () => {
      if (rendererRef.current) {
        rendererRef.current.resize(window.innerWidth, window.innerHeight);
      }
    };
    window.addEventListener('resize', handleResize);

    // Cleanup
    return () => {
      window.removeEventListener('resize', handleResize);
      if (rendererRef.current) {
        rendererRef.current.dispose();
      }
      if (inputHandlerRef.current) {
        inputHandlerRef.current.dispose();
      }
    };
  }, []);

  // Update renderer when player position changes
  useEffect(() => {
    if (rendererRef.current && player?.position) {
      rendererRef.current.setCameraTarget(
        player.position.x,
        player.position.y,
        player.position.z
      );
    }
  }, [player?.position]);

  return (
    <div className="game-canvas-container">
      <canvas ref={canvasRef} id="game-canvas" />
    </div>
  );
};
