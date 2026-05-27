"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import {
  Activity,
  ArrowRight,
  Boxes,
  Braces,
  CheckCircle2,
  CircleDotDashed,
  Clock3,
  Code2,
  Download,
  FileJson,
  Gauge,
  GitBranch,
  Map,
  Mic2,
  MousePointer2,
  Play,
  SquarePen,
  Terminal,
  Video
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle
} from "@/components/ui/card";
import { cn } from "@/lib/utils";

const navItems = [
  ["Overview", "#overview"],
  ["Features", "#features"],
  ["Setup", "#setup"],
  ["Workflow", "#workflow"],
  ["Outputs", "#outputs"]
];

const features = [
  {
    icon: Clock3,
    title: "Event timeline",
    text: "Click, zoom, pan, tool, and annotation events are captured with millisecond timestamps."
  },
  {
    icon: Map,
    title: "View-state capture",
    text: "Each event carries x, y, width, height, z, t, and downsample so the slide view remains reproducible."
  },
  {
    icon: SquarePen,
    title: "Annotation geometry",
    text: "Annotation events include ROI type, bounds, point count, and exported vertices."
  },
  {
    icon: Activity,
    title: "Live monitor",
    text: "A QuPath analysis tab gives start, pause, clear, transcript, status, and export controls."
  },
  {
    icon: Mic2,
    title: "Transcript sync",
    text: "Optional faster-whisper transcription follows microphone audio with live level feedback."
  },
  {
    icon: Download,
    title: "Export package",
    text: "Save event CSV, event JSON, mouse movement JSON, transcript text, and edited transcript copies."
  }
];

const setupSteps = [
  {
    title: "Install Java 21 and QuPath",
    text: "Use a Java 21 JDK and a compatible QuPath installation before building the extension.",
    command: null
  },
  {
    title: "Build the extension jar",
    text: "Run the Gradle wrapper from the repository root.",
    command: "./gradlew build"
  },
  {
    title: "Drag the jar into QuPath",
    text: "Install the generated artifact directly into an open QuPath window.",
    command: "build/libs/TimeStamp-0.1.0-SNAPSHOT.jar"
  },
  {
    title: "Verify the extension",
    text: "Confirm both the extension menu and the analysis monitor are available.",
    command: "Extensions > TimeStamp Extension\nAnalysis tab > TimeStamp Monitor"
  }
];

const workflow = [
  {
    title: "Prepare session",
    text: "Create folders and expected output names.",
    code: "./scripts/prepare_demo.sh <demo_name>"
  },
  {
    title: "Select folder",
    text: "Point TimeStamp at the session folder.",
    code: "Select transcript session folder"
  },
  {
    title: "Start recording",
    text: "Begin event capture and optional transcription.",
    code: "TimeStamp Monitor > Start Recording"
  },
  {
    title: "Review slide",
    text: "Zoom, pan, change tools, and annotate regions.",
    code: "Zoom / Pan / Annotate"
  },
  {
    title: "Pause",
    text: "Stop capture while keeping logs available.",
    code: "Pause Recording"
  },
  {
    title: "Export",
    text: "Write structured logs and transcript files.",
    code: "JSON / CSV / TXT"
  }
];

const outputs = [
  {
    icon: FileJson,
    name: "*_event.json",
    title: "Semantic events",
    text: "Interaction events, details, view bounds, and annotation geometry."
  },
  {
    icon: MousePointer2,
    name: "*_cursor.json",
    title: "Mouse path",
    text: "Optional throttled cursor movement for high-fidelity review."
  },
  {
    icon: Video,
    name: "*_video.mp4",
    title: "Screen recording",
    text: "Reference video that preserves visual context and narration."
  },
  {
    icon: Braces,
    name: "*_transcript.txt",
    title: "Narration text",
    text: "Searchable live, final, or edited transcript output."
  }
];

const fadeUp = {
  hidden: { opacity: 0, y: 22 },
  visible: { opacity: 1, y: 0 }
};

function SectionIntro({
  eyebrow,
  title,
  text
}: {
  eyebrow: string;
  title: string;
  text: string;
}) {
  return (
    <motion.div
      className="mb-8 flex flex-col gap-4 md:flex-row md:items-end md:justify-between"
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: "-80px" }}
      variants={fadeUp}
      transition={{ duration: 0.5 }}
    >
      <div className="max-w-2xl">
        <p className="mb-3 flex items-center gap-2 text-xs font-bold uppercase tracking-[0.14em] text-primary">
          <span className="h-2 w-2 rounded-sm bg-amber" />
          {eyebrow}
        </p>
        <h2 className="text-3xl font-semibold tracking-tight md:text-5xl">{title}</h2>
      </div>
      <p className="max-w-xl text-sm leading-6 text-muted-foreground md:text-base">{text}</p>
    </motion.div>
  );
}

function CodeBlock({ children }: { children: string }) {
  return (
    <pre className="mt-4 overflow-x-auto rounded-lg bg-[#1f2a2d] p-4 text-sm leading-6 text-white">
      <code>{children}</code>
    </pre>
  );
}

function ProductPreview() {
  const eventRows = [
    ["10:03:12.018", "Zoom In Start", "downsample=4.00"],
    ["10:03:13.086", "Pan End", "x=821.4, y=402.7"],
    ["10:03:14.221", "Annotate", "points=9, z=0, t=0"]
  ];

  return (
    <motion.div
      className="overflow-hidden rounded-lg border bg-card shadow-instrument"
      initial={{ opacity: 0, scale: 0.97, y: 18 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      transition={{ delay: 0.18, duration: 0.65, ease: "easeOut" }}
    >
      <div className="flex h-11 items-center justify-between border-b px-4 text-xs font-semibold text-muted-foreground">
        <span>TimeStamp Monitor</span>
        <div className="flex gap-1.5" aria-hidden="true">
          <span className="h-2 w-2 rounded-full bg-border" />
          <span className="h-2 w-2 rounded-full bg-border" />
          <span className="h-2 w-2 rounded-full bg-border" />
        </div>
      </div>
      <div className="grid min-h-[430px] lg:grid-cols-[1fr_290px]">
        <div className="microscopy-field relative min-h-[360px] overflow-hidden">
          <div className="absolute inset-[-80px] rotate-2 opacity-90 before:absolute before:inset-0 before:content-['']" />
          <motion.div
            className="absolute left-5 top-5 z-10 rounded-md bg-[#1f2a2d]/90 px-3 py-2 font-mono text-xs text-white shadow-panel"
            animate={{ y: [0, -2, 0] }}
            transition={{ duration: 2.4, repeat: Infinity, ease: "easeInOut" }}
          >
            10:03:14.221
            <span className="block text-amber">Last: Annotate</span>
          </motion.div>
          <motion.div
            className="absolute bottom-24 right-16 z-10 h-32 w-48 rounded-lg border-2 border-dashed border-primary bg-primary/10"
            initial={{ pathLength: 0 }}
            animate={{ scale: [1, 1.015, 1] }}
            transition={{ duration: 2.8, repeat: Infinity, ease: "easeInOut" }}
          >
            <span className="absolute -left-1.5 -top-1.5 h-3 w-3 rounded-sm border-2 border-white bg-amber" />
            <span className="absolute -bottom-1.5 -right-1.5 h-3 w-3 rounded-sm border-2 border-white bg-amber" />
          </motion.div>
          <motion.div
            className="absolute bottom-12 left-8 z-10 rounded-md border bg-white/90 px-3 py-2 text-xs text-muted-foreground shadow-panel"
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: 0.7, duration: 0.5 }}
          >
            zoom_view: x=412.6, y=208.4, w=1180.0, h=760.0
          </motion.div>
        </div>
        <div className="border-t bg-white lg:border-l lg:border-t-0">
          <div className="border-b p-4">
            <div className="flex items-center gap-2 text-sm font-bold text-teal-ink">
              <span className="h-2.5 w-2.5 rounded-sm bg-red-500" />
              Recording active
            </div>
          </div>
          <div className="border-b p-4">
            <p className="mb-3 text-[11px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
              Event log
            </p>
            <div className="space-y-3">
              {eventRows.map(([time, name, meta]) => (
                <div key={time} className="border-t pt-3 text-xs">
                  <Badge variant="amber" className="mb-2 font-mono">
                    {time}
                  </Badge>
                  <p className="font-bold">{name}</p>
                  <p className="font-mono text-muted-foreground">{meta}</p>
                </div>
              ))}
            </div>
          </div>
          <div className="p-4">
            <p className="mb-3 text-[11px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
              Live transcript
            </p>
            <p className="font-mono text-xs leading-5 text-muted-foreground">
              [10:03:14.802] Here I mark the region we will review after export.
            </p>
          </div>
        </div>
      </div>
    </motion.div>
  );
}

export default function Home() {
  return (
    <main className="min-h-screen overflow-hidden">
      <header className="sticky top-0 z-50 border-b bg-background/90 backdrop-blur-xl">
        <div className="container flex min-h-16 items-center justify-between gap-4 py-3">
          <Link href="#overview" className="flex items-center gap-3 font-bold">
            <span className="grid h-9 w-9 place-items-center rounded-lg border border-primary/25 bg-teal-soft text-primary">
              TS
            </span>
            <span>TimeStamp Extension</span>
          </Link>
          <nav className="hidden items-center gap-5 text-sm text-muted-foreground md:flex">
            {navItems.map(([label, href]) => (
              <Link key={href} href={href} className="transition-colors hover:text-primary">
                {label}
              </Link>
            ))}
          </nav>
          <Button asChild size="sm">
            <Link href="#setup">Set up extension</Link>
          </Button>
        </div>
      </header>

      <section id="overview" className="container grid gap-12 py-16 md:py-24 lg:grid-cols-[0.92fr_1.08fr] lg:items-center">
        <motion.div initial="hidden" animate="visible" variants={fadeUp} transition={{ duration: 0.6 }}>
          <Badge variant="outline" className="mb-5 gap-2">
            <CircleDotDashed className="h-3.5 w-3.5 text-primary" />
            QuPath recording toolkit
          </Badge>
          <h1 className="max-w-3xl text-5xl font-semibold leading-[0.98] tracking-tight md:text-7xl">
            TimeStamp Extension for QuPath
          </h1>
          <p className="mt-6 max-w-2xl text-lg leading-8 text-muted-foreground">
            Capture synchronized QuPath interactions, view bounds, annotation geometry, and live
            transcripts for reviewable whole-slide image demos.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Button asChild size="lg">
              <Link href="#setup">
                Build and install <ArrowRight className="h-4 w-4" />
              </Link>
            </Button>
            <Button asChild variant="outline" size="lg">
              <Link href="#workflow">
                See workflow <Play className="h-4 w-4" />
              </Link>
            </Button>
          </div>
          <div className="mt-6 flex flex-wrap gap-2">
            <Badge>QuPath extension</Badge>
            <Badge>JSON + CSV exports</Badge>
            <Badge>Live transcript optional</Badge>
          </div>
        </motion.div>
        <ProductPreview />
      </section>

      <section id="features" className="border-y bg-muted/70 py-16 md:py-24">
        <div className="container">
          <SectionIntro
            eyebrow="Core capabilities"
            title="Purpose-built for reproducible review sessions"
            text="The extension records what happened in the viewer, where it happened on the slide, and what was said while it happened."
          />
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {features.map((feature, index) => (
              <motion.div
                key={feature.title}
                initial="hidden"
                whileInView="visible"
                viewport={{ once: true, margin: "-80px" }}
                variants={fadeUp}
                transition={{ delay: index * 0.04, duration: 0.45 }}
              >
                <Card className="h-full transition-colors hover:border-primary/35">
                  <CardHeader>
                    <div className="mb-4 grid h-10 w-10 place-items-center rounded-lg bg-teal-soft text-primary">
                      <feature.icon className="h-5 w-5" />
                    </div>
                    <CardTitle>{feature.title}</CardTitle>
                    <CardDescription>{feature.text}</CardDescription>
                  </CardHeader>
                </Card>
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      <section id="setup" className="container py-16 md:py-24">
        <SectionIntro
          eyebrow="Installation pipeline"
          title="Build the jar, install in QuPath, then turn on optional transcription"
          text="The Java extension remains the source of truth. The website documents the build output and the exact local setup path."
        />
        <div className="grid gap-5 lg:grid-cols-[0.95fr_1.05fr]">
          <div className="grid gap-4">
            {setupSteps.map((step, index) => (
              <Card key={step.title}>
                <CardHeader className="grid grid-cols-[2.5rem_1fr] gap-4 space-y-0">
                  <div className="grid h-9 w-9 place-items-center rounded-lg bg-primary font-bold text-primary-foreground">
                    {index + 1}
                  </div>
                  <div>
                    <CardTitle>{step.title}</CardTitle>
                    <CardDescription>{step.text}</CardDescription>
                    {step.command ? <CodeBlock>{step.command}</CodeBlock> : null}
                  </div>
                </CardHeader>
              </Card>
            ))}
          </div>
          <Card className="bg-amber-soft/80">
            <CardHeader>
              <div className="mb-4 grid h-10 w-10 place-items-center rounded-lg bg-white text-amber-foreground">
                <Terminal className="h-5 w-5" />
              </div>
              <CardTitle>Optional transcript environment</CardTitle>
              <CardDescription className="text-amber-foreground/80">
                Live transcription uses the repository scripts with faster-whisper, sounddevice, and
                numpy in a local virtual environment.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <CodeBlock>{`python3 -m venv .venv-whisper
source .venv-whisper/bin/activate
python -m pip install --upgrade pip
python -m pip install faster-whisper sounddevice numpy`}</CodeBlock>
            </CardContent>
          </Card>
        </div>
      </section>

      <section id="workflow" className="border-y bg-muted/70 py-16 md:py-24">
        <div className="container">
          <SectionIntro
            eyebrow="Workflow"
            title="From prepared session to synchronized review package"
            text="The workflow mirrors the codebase: prepare output folders, capture viewer interactions, export structured data, and review the files together."
          />
          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-6">
            {workflow.map((item, index) => (
              <motion.div
                key={item.title}
                initial={{ opacity: 0, y: 18 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true, margin: "-80px" }}
                transition={{ delay: index * 0.05, duration: 0.45 }}
              >
                <Card className="h-full">
                  <CardHeader>
                    <div className="mb-5 flex items-center justify-between">
                      <span className="font-mono text-sm font-bold text-primary">
                        {String(index + 1).padStart(2, "0")}
                      </span>
                      {index < workflow.length - 1 ? (
                        <ArrowRight className="hidden h-4 w-4 text-border lg:block" />
                      ) : (
                        <CheckCircle2 className="h-4 w-4 text-primary" />
                      )}
                    </div>
                    <CardTitle className="text-base">{item.title}</CardTitle>
                    <CardDescription>{item.text}</CardDescription>
                    <p className="pt-3 font-mono text-xs leading-5 text-teal-ink">{item.code}</p>
                  </CardHeader>
                </Card>
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      <section id="outputs" className="container py-16 md:py-24">
        <SectionIntro
          eyebrow="Output package"
          title="Consistent files for downstream analysis"
          text="Use the exported package to compare semantic events, cursor trails, video context, and searchable narration."
        />
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {outputs.map((output, index) => (
            <motion.div
              key={output.name}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-80px" }}
              variants={fadeUp}
              transition={{ delay: index * 0.05, duration: 0.45 }}
            >
              <Card className="h-full">
                <CardHeader>
                  <div className="mb-4 flex items-center justify-between">
                    <Badge variant="amber" className="font-mono">
                      {output.name}
                    </Badge>
                    <output.icon className="h-5 w-5 text-primary" />
                  </div>
                  <CardTitle>{output.title}</CardTitle>
                  <CardDescription>{output.text}</CardDescription>
                </CardHeader>
              </Card>
            </motion.div>
          ))}
        </div>
      </section>

      <section className="border-y bg-[#1f2a2d] py-14 text-white">
        <div className="container grid gap-8 md:grid-cols-[1fr_auto] md:items-center">
          <div>
            <p className="mb-2 text-sm font-semibold text-amber">Repository-ready website</p>
            <h2 className="text-3xl font-semibold tracking-tight">
              Built for lab demos, annotation walkthroughs, and reproducible review sessions.
            </h2>
          </div>
          <div className="flex flex-wrap gap-3">
            <Button asChild variant="outline" className="border-white/40 text-white hover:bg-white/10">
              <Link href="https://github.com/nighthunter57/QuPath_TimeStamp_extension">
                <GitBranch className="h-4 w-4" />
                Repository
              </Link>
            </Button>
            <Button asChild className="bg-white text-[#1f2a2d] hover:bg-white/90">
              <Link href="#setup">
                <Code2 className="h-4 w-4" />
                Install steps
              </Link>
            </Button>
          </div>
        </div>
      </section>

      <footer className="container flex flex-col gap-3 py-8 text-sm text-muted-foreground md:flex-row md:items-center md:justify-between">
        <p>TimeStamp Extension for QuPath</p>
        <div className="flex items-center gap-2">
          <Boxes className="h-4 w-4" />
          Next.js, Tailwind CSS, shadcn/ui patterns, and Framer Motion
        </div>
      </footer>
    </main>
  );
}
