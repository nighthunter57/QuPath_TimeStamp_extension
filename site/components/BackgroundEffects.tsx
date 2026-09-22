"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";

export function BackgroundEffects() {
  const [mousePos, setMousePos] = useState({ x: 0, y: 0 });

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      setMousePos({ x: e.clientX, y: e.clientY });
    };
    window.addEventListener("mousemove", handleMouseMove);
    return () => window.removeEventListener("mousemove", handleMouseMove);
  }, []);

  return (
    <div className="pointer-events-none fixed inset-0 z-0 overflow-hidden select-none">
      {/* Interactive Cursor Glow Spotlight */}
      <div
        className="absolute h-[450px] w-[450px] rounded-full opacity-20 blur-[100px] transition-transform duration-300 ease-out"
        style={{
          background: "radial-gradient(circle, rgba(0, 180, 216, 0.6) 0%, rgba(114, 9, 183, 0.3) 50%, transparent 70%)",
          transform: `translate(${mousePos.x - 225}px, ${mousePos.y - 225}px)`
        }}
      />

      {/* Floating Ambient Glowing Gradient Orbs */}
      <motion.div
        className="absolute -top-24 left-1/4 h-96 w-96 rounded-full bg-cyan-500/15 blur-[120px]"
        animate={{
          x: [0, 60, -40, 0],
          y: [0, 40, 70, 0],
          scale: [1, 1.2, 0.9, 1]
        }}
        transition={{
          duration: 18,
          repeat: Infinity
        }}
      />

      <motion.div
        className="absolute top-1/3 -right-20 h-96 w-96 rounded-full bg-teal-500/15 blur-[120px]"
        animate={{
          x: [0, -50, 40, 0],
          y: [0, -60, -30, 0],
          scale: [1, 0.85, 1.15, 1]
        }}
        transition={{
          duration: 22,
          repeat: Infinity
        }}
      />

      <motion.div
        className="absolute bottom-10 left-10 h-80 w-80 rounded-full bg-amber-500/10 blur-[100px]"
        animate={{
          x: [0, 40, -30, 0],
          y: [0, -30, 50, 0]
        }}
        transition={{
          duration: 15,
          repeat: Infinity
        }}
      />

      {/* Subtle Grid Lines Overlay */}
      <div
        className="absolute inset-0 opacity-[0.03] dark:opacity-[0.07]"
        style={{
          backgroundImage: `
            linear-gradient(to right, currentColor 1px, transparent 1px),
            linear-gradient(to bottom, currentColor 1px, transparent 1px)
          `,
          backgroundSize: "40px 40px"
        }}
      />
    </div>
  );
}
