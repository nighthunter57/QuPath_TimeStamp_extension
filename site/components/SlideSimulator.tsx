"use client";

import { useState, useEffect, useRef } from "react";
import { motion, AnimatePresence } from "framer-motion";
import {
  Play,
  Pause,
  RotateCcw,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Mic,
  Activity,
  Layers,
  Square,
  Circle,
  Pencil,
  Sparkles,
  Volume2,
  MousePointer,
  CheckCircle,
  Clock
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

interface DemoEvent {
  id: number;
  time: string;
  type: "ZOOM" | "PAN" | "TOOL" | "ANNOTATION" | "TRANSCRIPT";
  title: string;
  details: string;
  zoomLevel: number;
  cursorPos: { x: number; y: number };
  activeTool: string;
  transcriptText?: string;
}

const DEMO_PLAYBACK_STEPS: DemoEvent[] = [
  {
    id: 1,
    time: "10:02:01",
    type: "PAN",
    title: "Pan to Upper Left Tissue Quadrant",
    details: "Moving viewport to inspect glomerular structures",
    zoomLevel: 4,
    cursorPos: { x: 30, y: 35 },
    activeTool: "Move Tool"
  },
  {
    id: 2,
    time: "10:02:06",
    type: "ZOOM",
    title: "Zoomed to 20x Magnification",
    details: "High power detail view on cellular morphology",
    zoomLevel: 20,
    cursorPos: { x: 45, y: 40 },
    activeTool: "Move Tool"
  },
  {
    id: 3,
    time: "10:02:12",
    type: "TOOL",
    title: "Selected Polygon ROI Tool",
    details: "Delineating region of Interest for quantitative scoring",
    zoomLevel: 20,
    cursorPos: { x: 50, y: 42 },
    activeTool: "Polygon ROI"
  },
  {
    id: 4,
    time: "10:02:19",
    type: "ANNOTATION",
    title: "Created Tumor ROI Annotation #1",
    details: "Annotated region area: 1.84 mm²",
    zoomLevel: 20,
    cursorPos: { x: 55, y: 48 },
    activeTool: "Polygon ROI"
  },
  {
    id: 5,
    time: "10:02:27",
    type: "TRANSCRIPT",
    title: "Audio Transcript Recorded",
    details: "Voice note captured by extension microphone listener",
    zoomLevel: 20,
    cursorPos: { x: 58, y: 50 },
    activeTool: "Polygon ROI",
    transcriptText: "High cellular density detected with irregular nuclear membranes along the margin."
  },
  {
    id: 6,
    time: "10:02:35",
    type: "ZOOM",
    title: "Zoomed to 40x Magnification",
    details: "Maximum magnification detail check for mitotic figures",
    zoomLevel: 40,
    cursorPos: { x: 62, y: 54 },
    activeTool: "Polygon ROI"
  },
  {
    id: 7,
    time: "10:02:45",
    type: "ANNOTATION",
    title: "Marked High-Power Mitosis Annotation #2",
    details: "Annotated mitotic count marker at (x: 62%, y: 54%)",
    zoomLevel: 40,
    cursorPos: { x: 64, y: 55 },
    activeTool: "Brush Tool"
  }
];

export function SlideSimulator() {
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const [isPlaying, setIsPlaying] = useState(true);
  const [userZoom, setUserZoom] = useState(1);
  const [selectedTool, setSelectedTool] = useState("Move Tool");
  const [annotations, setAnnotations] = useState<Array<{ id: number; x: number; y: number; label: string }>>([
    { id: 1, x: 45, y: 40, label: "ROI #1 (1.84mm²)" }
  ]);

  const activeEvent = DEMO_PLAYBACK_STEPS[currentStepIndex];
  const tissuePanX = (50 - activeEvent.cursorPos.x) / 4;
  const tissuePanY = (50 - activeEvent.cursorPos.y) / 4;

  // Auto playback ticker
  useEffect(() => {
    if (!isPlaying) return;
    const interval = setInterval(() => {
      setCurrentStepIndex((prev) => (prev + 1) % DEMO_PLAYBACK_STEPS.length);
    }, 3200);
    return () => clearInterval(interval);
  }, [isPlaying]);

  // Sync state with active event step
  useEffect(() => {
    if (activeEvent.type === "ANNOTATION" && !annotations.some((a) => a.id === activeEvent.id)) {
      setAnnotations((prev) => [
        ...prev,
        { id: activeEvent.id, x: activeEvent.cursorPos.x, y: activeEvent.cursorPos.y, label: activeEvent.title }
      ]);
    }
  }, [currentStepIndex, activeEvent, annotations]);

  const resetSimulator = () => {
    setCurrentStepIndex(0);
    setAnnotations([{ id: 1, x: 45, y: 40, label: "ROI #1 (1.84mm²)" }]);
    setIsPlaying(true);
  };

  return (
    <div className="relative overflow-hidden rounded-2xl border border-primary/30 bg-card/90 shadow-2xl backdrop-blur-md">
      {/* Top Header Bar */}
      <div className="flex h-14 items-center justify-between border-b border-border/80 bg-muted/40 px-5 text-xs font-semibold">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 text-foreground font-bold">
            <span className="h-2.5 w-2.5 rounded-full bg-emerald-500 animate-pulse" />
            Live QuPath TimeStamp Viewport Simulator
          </div>
          <Badge variant="outline" className="hidden sm:inline-flex border-primary/30 text-primary">
            Slide_Biopsy_984.svs (Whole Slide Image)
          </Badge>
        </div>

        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="ghost"
            className="h-8 gap-1.5 text-xs"
            onClick={() => setIsPlaying(!isPlaying)}
          >
            {isPlaying ? <Pause className="h-3.5 w-3.5 text-amber-500" /> : <Play className="h-3.5 w-3.5 text-emerald-500" />}
            <span>{isPlaying ? "Pause Session" : "Play Session"}</span>
          </Button>

          <Button
            size="sm"
            variant="ghost"
            className="h-8 gap-1.5 text-xs text-muted-foreground hover:text-foreground"
            onClick={resetSimulator}
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Reset
          </Button>
        </div>
      </div>

      {/* Main Viewport & Sidebar Grid */}
      <div className="grid lg:grid-cols-12 min-h-[440px]">
        {/* Left Interactive Microscopy Canvas */}
        <div className="lg:col-span-8 relative overflow-hidden bg-slate-950 flex flex-col justify-between p-6 select-none">
          {/* Ambient Histology Digital Tissue Canvas Background */}
          <div
            className="absolute inset-0 transition-transform duration-700 ease-out"
            style={{
              transform: `scale(${1 + activeEvent.zoomLevel / 30}) translate(${tissuePanX}%, ${tissuePanY}%)`,
              backgroundImage: `
                radial-gradient(circle at ${activeEvent.cursorPos.x}% ${activeEvent.cursorPos.y}%, rgba(236, 72, 153, 0.35) 0%, rgba(139, 92, 246, 0.25) 25%, transparent 60%),
                radial-gradient(circle at 30% 70%, rgba(59, 130, 246, 0.3) 0%, transparent 50%),
                radial-gradient(circle at 80% 20%, rgba(16, 185, 129, 0.25) 0%, transparent 45%)
              `
            }}
          >
            {/* Grid Pattern Overlay */}
            <div
              className="absolute inset-0 opacity-20"
              style={{
                backgroundImage: `radial-gradient(circle at 1px 1px, rgba(255, 255, 255, 0.4) 1px, transparent 0)`,
                backgroundSize: "28px 28px"
              }}
            />
          </div>

          {/* Canvas Floating Top Controls */}
          <div className="relative z-10 flex items-center justify-between">
            <div className="flex items-center gap-2 rounded-lg bg-black/70 border border-white/10 px-3 py-1.5 backdrop-blur-md text-white font-mono text-xs shadow-lg">
              <Clock className="h-3.5 w-3.5 text-amber-400" />
              <span>{activeEvent.time}</span>
              <span className="text-white/40">|</span>
              <span className="text-emerald-400 font-bold">{activeEvent.zoomLevel}x Magnification</span>
            </div>

            <div className="flex items-center gap-1 rounded-lg bg-black/70 border border-white/10 p-1 backdrop-blur-md text-white text-xs">
              <button
                onClick={() => setSelectedTool("Move Tool")}
                className={`p-1.5 rounded transition-colors ${selectedTool === "Move Tool" ? "bg-primary text-primary-foreground" : "hover:bg-white/10 text-white/70"}`}
                title="Move/Pan Viewport"
              >
                <MousePointer className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={() => setSelectedTool("Polygon ROI")}
                className={`p-1.5 rounded transition-colors ${selectedTool === "Polygon ROI" ? "bg-primary text-primary-foreground" : "hover:bg-white/10 text-white/70"}`}
                title="Polygon ROI Tool"
              >
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={() => setSelectedTool("Rectangle Tool")}
                className={`p-1.5 rounded transition-colors ${selectedTool === "Rectangle Tool" ? "bg-primary text-primary-foreground" : "hover:bg-white/10 text-white/70"}`}
                title="Rectangle Tool"
              >
                <Square className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>

          {/* Dynamic Animated Pointer & Annotation Overlay */}
          <div className="relative z-10 min-h-[260px] my-auto pointer-events-none">
            {/* Animated Cursor */}
            <motion.div
              className="absolute z-30 flex items-center gap-2 text-white"
              animate={{
                left: `${activeEvent.cursorPos.x}%`,
                top: `${activeEvent.cursorPos.y}%`
              }}
              transition={{ duration: 0.8 }}
            >
              <div className="relative">
                <MousePointer className="h-5 w-5 text-amber-400 drop-shadow-[0_0_8px_rgba(251,191,36,0.8)] fill-amber-400/30" />
                <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500" />
                </span>
              </div>
              <div className="rounded bg-black/80 border border-white/20 px-2 py-0.5 font-mono text-[10px] text-amber-300 backdrop-blur-sm">
                Dr. Reviewer
              </div>
            </motion.div>

            {/* Dynamic Rendered Annotations */}
            {annotations.map((anno) => (
              <motion.div
                key={anno.id}
                initial={{ scale: 0, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                className="absolute z-20"
                style={{ left: `${anno.x}%`, top: `${anno.y}%` }}
              >
                <div className="relative -translate-x-1/2 -translate-y-1/2">
                  <div className="w-24 h-16 rounded-xl border-2 border-dashed border-teal-400 bg-teal-500/20 backdrop-blur-[1px] animate-pulse" />
                  <div className="absolute -bottom-6 left-0 rounded bg-teal-950/90 border border-teal-400/40 px-2 py-0.5 text-[10px] font-bold text-teal-200 whitespace-nowrap">
                    {anno.label}
                  </div>
                </div>
              </motion.div>
            ))}
          </div>

          {/* Speech Transcript Waveform Bar */}
          <AnimatePresence>
            {activeEvent.transcriptText && (
              <motion.div
                initial={{ opacity: 0, y: 15 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: 15 }}
                className="relative z-10 flex items-center gap-3 rounded-xl border border-emerald-500/30 bg-black/80 p-3 backdrop-blur-md shadow-lg"
              >
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-500/20 text-emerald-400">
                  <Mic className="h-4 w-4 animate-bounce" />
                </div>
                <div className="flex-1 text-xs">
                  <div className="flex items-center gap-2 text-emerald-400 font-bold uppercase text-[10px]">
                    <span>Audio Note Recorded</span>
                    <span className="flex gap-0.5 items-center">
                      <span className="h-2 w-0.5 bg-emerald-400 animate-pulse" />
                      <span className="h-3.5 w-0.5 bg-emerald-400 animate-pulse delay-75" />
                      <span className="h-1.5 w-0.5 bg-emerald-400 animate-pulse delay-150" />
                    </span>
                  </div>
                  <p className="italic text-slate-200 text-xs mt-0.5">&ldquo;{activeEvent.transcriptText}&rdquo;</p>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* Right Event Ticker & Live Panel */}
        <div className="lg:col-span-4 border-l border-border bg-card p-5 flex flex-col justify-between space-y-4">
          <div>
            <div className="flex items-center justify-between border-b border-border pb-3 mb-4">
              <span className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                <Activity className="h-4 w-4 text-primary" />
                Live Recorded Stream
              </span>
              <Badge variant="outline" className="font-mono text-[10px]">
                {currentStepIndex + 1} / {DEMO_PLAYBACK_STEPS.length} Steps
              </Badge>
            </div>

            {/* Active Event Highlight Box */}
            <motion.div
              key={activeEvent.id}
              initial={{ opacity: 0, x: 10 }}
              animate={{ opacity: 1, x: 0 }}
              className="rounded-xl border border-primary/30 bg-primary/5 p-4 space-y-2 mb-4"
            >
              <div className="flex items-center justify-between">
                <Badge variant="amber" className="text-[10px]">
                  {activeEvent.type}
                </Badge>
                <span className="font-mono text-xs font-semibold text-primary">{activeEvent.time}</span>
              </div>
              <h4 className="text-sm font-bold text-foreground">{activeEvent.title}</h4>
              <p className="text-xs text-muted-foreground leading-relaxed">{activeEvent.details}</p>
            </motion.div>

            {/* Timeline History List */}
            <div className="space-y-2 max-h-[220px] overflow-y-auto pr-1 text-xs">
              {DEMO_PLAYBACK_STEPS.map((ev, idx) => (
                <button
                  key={ev.id}
                  onClick={() => {
                    setCurrentStepIndex(idx);
                    setIsPlaying(false);
                  }}
                  className={`w-full text-left flex items-start gap-2.5 p-2.5 rounded-lg border transition-all ${
                    idx === currentStepIndex
                      ? "border-primary bg-primary/10 font-semibold"
                      : "border-border/60 hover:bg-muted/40 text-muted-foreground"
                  }`}
                >
                  <span className="font-mono text-[11px] shrink-0 text-primary">{ev.time}</span>
                  <div className="truncate">
                    <span className="text-foreground font-medium block truncate">{ev.title}</span>
                  </div>
                </button>
              ))}
            </div>
          </div>

          {/* Footer Callout */}
          <div className="rounded-lg border border-border bg-muted/30 p-3 text-[11px] text-muted-foreground flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-amber-500 shrink-0" />
            <span>Events are recorded locally without modifying slide data.</span>
          </div>
        </div>
      </div>
    </div>
  );
}
