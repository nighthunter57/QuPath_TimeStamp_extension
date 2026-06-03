"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  Activity,
  Apple,
  ArrowRight,
  BadgeCheck,
  Boxes,
  CircleHelp,
  Clock3,
  Download,
  ExternalLink,
  FileDown,
  FileJson,
  Laptop,
  Mic2,
  Monitor,
  MousePointer2,
  Play,
  ShieldCheck,
  SquarePen,
  Stethoscope,
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

const QUPATH_VERSION = "0.7.0";
const extensionDownloadUrl = "downloads/TimeStamp-0.1.0-SNAPSHOT.jar";

const navItems = [
  ["Download", "#downloads"],
  ["Install", "#install"],
  ["What it does", "#features"],
  ["Session", "#session"],
  ["Help", "#help"]
];

const qupathDownloads = [
  {
    os: "Windows",
    key: "windows",
    label: "Download QuPath for Windows",
    detail: "Windows installer (.msi)",
    size: "271 MB",
    href: `https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/QuPath-v${QUPATH_VERSION}-Windows.msi`,
    icon: Monitor
  },
  {
    os: "Windows",
    key: "windows-portable",
    label: "Windows portable",
    detail: "No installer (.zip)",
    size: "270 MB",
    href: `https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/QuPath-v${QUPATH_VERSION}-Windows.zip`,
    icon: Monitor
  },
  {
    os: "macOS",
    key: "mac-intel",
    label: "Download QuPath for macOS Intel",
    detail: "Older Intel Macs (.pkg)",
    size: "241 MB",
    href: `https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/QuPath-v${QUPATH_VERSION}-Mac-x64.pkg`,
    icon: Apple
  },
  {
    os: "macOS",
    key: "mac-apple-silicon",
    label: "Download QuPath for macOS Apple silicon",
    detail: "M1, M2, M3, or newer Macs (.pkg)",
    size: "230 MB",
    href: `https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/QuPath-v${QUPATH_VERSION}-Mac-arm64.pkg`,
    icon: Apple
  },
  {
    os: "Linux",
    key: "linux",
    label: "Download QuPath for Linux",
    detail: "Linux archive (.tar.xz)",
    size: "244 MB",
    href: `https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/QuPath-v${QUPATH_VERSION}-Linux.tar.xz`,
    icon: Laptop
  }
];

const installSteps = [
  {
    title: "Download QuPath",
    text: "Choose the button that matches the computer. If QuPath is already installed, skip this step.",
    icon: Download
  },
  {
    title: "Download TimeStamp Extension",
    text: "The extension is a small .jar file. Keep it somewhere easy to find, like Downloads.",
    icon: FileDown
  },
  {
    title: "Open QuPath",
    text: "Start QuPath like any other application. No coding or terminal window is needed.",
    icon: Play
  },
  {
    title: "Drag the extension into QuPath",
    text: "Drag the downloaded .jar file onto the QuPath window. QuPath will ask to install it.",
    icon: MousePointer2
  },
  {
    title: "Restart and confirm",
    text: "Restart QuPath, then check Extensions > TimeStamp Extension or open the TimeStamp Monitor.",
    icon: BadgeCheck
  }
];

const features = [
  {
    icon: Clock3,
    title: "Records what happened",
    text: "Clicks, zooms, pans, tool changes, and annotation changes are saved in order."
  },
  {
    icon: SquarePen,
    title: "Tracks annotation moments",
    text: "When a region is drawn or removed, the extension records the shape and timing."
  },
  {
    icon: Activity,
    title: "Shows a live monitor",
    text: "Doctors can see recording status, recent events, and transcript status while reviewing slides."
  },
  {
    icon: Mic2,
    title: "Supports live notes",
    text: "Optional transcript capture makes spoken review comments searchable later."
  },
  {
    icon: Video,
    title: "Pairs with screen video",
    text: "Use a normal screen recorder while TimeStamp captures the structured event timeline."
  },
  {
    icon: FileJson,
    title: "Exports review files",
    text: "Export event logs, cursor movement, video, and transcript files for follow-up analysis."
  }
];

const sessionSteps = [
  "Start screen recording if you want video.",
  "Click Start Recording in the TimeStamp Monitor.",
  "Review the slide naturally: zoom, pan, discuss, and annotate.",
  "Click Pause Recording when finished.",
  "Export the event log, mouse log, and transcript for the research team."
];

const fadeUp = {
  hidden: { opacity: 0, y: 22 },
  visible: { opacity: 1, y: 0 }
};

type OsGroup = "windows" | "mac" | "linux" | "unknown";

function detectOs(): OsGroup {
  if (typeof navigator === "undefined") {
    return "unknown";
  }

  const platform = navigator.platform.toLowerCase();
  const userAgent = navigator.userAgent.toLowerCase();
  const source = `${platform} ${userAgent}`;

  if (source.includes("win")) {
    return "windows";
  }
  if (source.includes("mac")) {
    return "mac";
  }
  if (source.includes("linux")) {
    return "linux";
  }
  return "unknown";
}

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
      <div className="max-w-3xl">
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

function ProductPreview() {
  const eventRows = [
    ["10:03:12", "Zoomed into tissue region"],
    ["10:03:14", "Annotation added"],
    ["10:03:22", "Slide moved to next area"]
  ];

  return (
    <motion.div
      className="overflow-hidden rounded-lg border bg-card shadow-instrument"
      initial={{ opacity: 0, scale: 0.97, y: 18 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      transition={{ delay: 0.18, duration: 0.65, ease: "easeOut" }}
    >
      <div className="flex h-11 items-center justify-between border-b px-4 text-xs font-semibold text-muted-foreground">
        <span>Example review session</span>
        <Badge variant="amber">Recording</Badge>
      </div>
      <div className="grid min-h-[430px] lg:grid-cols-[1fr_290px]">
        <div className="microscopy-field relative min-h-[360px] overflow-hidden">
          <div className="absolute inset-[-80px] rotate-2 opacity-90 before:absolute before:inset-0 before:content-['']" />
          <motion.div
            className="absolute left-5 top-5 z-10 rounded-md bg-[#1f2a2d]/90 px-3 py-2 font-mono text-xs text-white shadow-panel"
            animate={{ y: [0, -2, 0] }}
            transition={{ duration: 2.4, repeat: Infinity, ease: "easeInOut" }}
          >
            10:03:14
            <span className="block text-amber">Last: Annotation added</span>
          </motion.div>
          <motion.div
            className="absolute bottom-24 right-16 z-10 h-32 w-48 rounded-lg border-2 border-dashed border-primary bg-primary/10"
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
            TimeStamp keeps a record while the doctor reviews the slide.
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
              Recent events
            </p>
            <div className="space-y-3">
              {eventRows.map(([time, name]) => (
                <div key={time} className="border-t pt-3 text-xs">
                  <Badge variant="amber" className="mb-2 font-mono">
                    {time}
                  </Badge>
                  <p className="font-bold">{name}</p>
                </div>
              ))}
            </div>
          </div>
          <div className="p-4">
            <p className="mb-3 text-[11px] font-bold uppercase tracking-[0.14em] text-muted-foreground">
              Spoken note
            </p>
            <p className="font-mono text-xs leading-5 text-muted-foreground">
              “This region is important for the follow-up review.”
            </p>
          </div>
        </div>
      </div>
    </motion.div>
  );
}

function DownloadCard({
  option,
  recommended
}: {
  option: (typeof qupathDownloads)[number];
  recommended?: boolean;
}) {
  const Icon = option.icon;

  return (
    <Card className={cn("h-full transition-colors hover:border-primary/40", recommended && "border-primary bg-teal-soft/70")}>
      <CardHeader>
        <div className="mb-4 flex items-center justify-between gap-4">
          <div className="grid h-11 w-11 place-items-center rounded-lg bg-white text-primary shadow-sm">
            <Icon className="h-5 w-5" />
          </div>
          {recommended ? <Badge variant="amber">Recommended</Badge> : <Badge variant="outline">{option.os}</Badge>}
        </div>
        <CardTitle>{option.label}</CardTitle>
        <CardDescription>
          {option.detail} · {option.size}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Button asChild className="w-full">
          <a href={option.href} aria-label={`${option.label}, ${option.detail}, ${option.size}`}>
            <Download className="h-4 w-4" />
            Download
          </a>
        </Button>
      </CardContent>
    </Card>
  );
}

export default function Home() {
  const [os, setOs] = useState<OsGroup>("unknown");

  useEffect(() => {
    setOs(detectOs());
  }, []);

  const recommendedDownloads = useMemo(() => {
    if (os === "windows") {
      return qupathDownloads.filter((item) => item.key === "windows");
    }
    if (os === "mac") {
      return qupathDownloads.filter((item) => item.key === "mac-apple-silicon" || item.key === "mac-intel");
    }
    if (os === "linux") {
      return qupathDownloads.filter((item) => item.key === "linux");
    }
    return qupathDownloads.filter((item) => item.key === "windows" || item.key === "mac-apple-silicon");
  }, [os]);

  const recommendedKeys = new Set(recommendedDownloads.map((item) => item.key));

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
            <Link href="#downloads">Download</Link>
          </Button>
        </div>
      </header>

      <section id="overview" className="container grid gap-12 py-16 md:py-24 lg:grid-cols-[0.92fr_1.08fr] lg:items-center">
        <motion.div initial="hidden" animate="visible" variants={fadeUp} transition={{ duration: 0.6 }}>
          <Badge variant="outline" className="mb-5 gap-2">
            <Stethoscope className="h-3.5 w-3.5 text-primary" />
            For clinical slide review teams
          </Badge>
          <h1 className="max-w-3xl text-5xl font-semibold leading-[0.98] tracking-tight md:text-7xl">
            Record a QuPath slide review without touching code
          </h1>
          <p className="mt-6 max-w-2xl text-lg leading-8 text-muted-foreground">
            TimeStamp Extension helps doctors review whole-slide images while automatically saving
            the timing of zooms, pans, annotations, spoken notes, and exported review files.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Button asChild size="lg">
              <Link href="#downloads">
                Download now <ArrowRight className="h-4 w-4" />
              </Link>
            </Button>
            <Button asChild variant="outline" size="lg">
              <Link href="#install">
                How to install <Play className="h-4 w-4" />
              </Link>
            </Button>
          </div>
          <div className="mt-6 flex flex-wrap gap-2">
            <Badge>No coding</Badge>
            <Badge>Drag-and-drop install</Badge>
            <Badge>Works inside QuPath</Badge>
          </div>
        </motion.div>
        <ProductPreview />
      </section>

      <section id="downloads" className="border-y bg-muted/70 py-16 md:py-24">
        <div className="container">
          <SectionIntro
            eyebrow="Step 1"
            title="Download QuPath for your computer"
            text={`Choose the QuPath ${QUPATH_VERSION} installer that matches the doctor's device. The recommended option is selected from the browser when possible.`}
          />

          <div className="mb-5 grid gap-4 md:grid-cols-2">
            {recommendedDownloads.map((option) => (
              <DownloadCard key={option.key} option={option} recommended />
            ))}
          </div>

          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-5">
            {qupathDownloads.map((option) => (
              <DownloadCard key={option.key} option={option} recommended={recommendedKeys.has(option.key)} />
            ))}
          </div>

          <div className="mt-5 flex flex-wrap gap-3 text-sm text-muted-foreground">
            <a
              className="inline-flex items-center gap-2 font-semibold text-primary"
              href={`https://github.com/qupath/qupath/releases/tag/v${QUPATH_VERSION}`}
            >
              Release notes v{QUPATH_VERSION} <ExternalLink className="h-3.5 w-3.5" />
            </a>
            <a
              className="inline-flex items-center gap-2 font-semibold text-primary"
              href="https://qupath.readthedocs.io/en/stable/docs/intro/installation.html"
            >
              QuPath installation notes <ExternalLink className="h-3.5 w-3.5" />
            </a>
            <a
              className="inline-flex items-center gap-2 font-semibold text-primary"
              href="https://qupath.readthedocs.io/en/latest/docs/intro/installation.html#qupath-for-mac"
            >
              Not sure which Mac? <CircleHelp className="h-3.5 w-3.5" />
            </a>
          </div>

          <Card className="mt-10 border-primary bg-white">
            <CardHeader className="grid gap-6 lg:grid-cols-[1fr_auto] lg:items-center">
              <div>
                <Badge variant="amber" className="mb-4">Step 2</Badge>
                <CardTitle className="text-3xl">Download the TimeStamp Extension</CardTitle>
                <CardDescription className="mt-3 max-w-2xl text-base">
                  After QuPath is installed, download the extension file. This is the file you will
                  drag into QuPath.
                </CardDescription>
              </div>
              <div className="flex flex-col gap-3">
                <Button asChild size="lg">
                  <a href={extensionDownloadUrl} download aria-label="Download TimeStamp Extension jar file">
                    <FileDown className="h-4 w-4" />
                    Download TimeStamp Extension
                  </a>
                </Button>
                <Button asChild variant="outline">
                  <a href="https://github.com/nighthunter57/QuPath_TimeStamp_extension/releases">
                    Backup download page
                  </a>
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              <div className="rounded-lg border bg-muted/60 p-4 text-sm text-muted-foreground">
                If the extension download does not start, use the backup download page or ask the
                study coordinator for the TimeStamp extension file.
              </div>
            </CardContent>
          </Card>
        </div>
      </section>

      <section id="install" className="container py-16 md:py-24">
        <SectionIntro
          eyebrow="Install"
          title="Plug the extension into QuPath"
          text="This is written for doctors and clinical staff: no command line, no code, and no build step."
        />
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-5">
          {installSteps.map((step, index) => (
            <motion.div
              key={step.title}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-80px" }}
              variants={fadeUp}
              transition={{ delay: index * 0.04, duration: 0.45 }}
            >
              <Card className="h-full">
                <CardHeader>
                  <div className="mb-5 flex items-center justify-between">
                    <span className="font-mono text-sm font-bold text-primary">
                      {String(index + 1).padStart(2, "0")}
                    </span>
                    <step.icon className="h-5 w-5 text-primary" />
                  </div>
                  <CardTitle className="text-base">{step.title}</CardTitle>
                  <CardDescription>{step.text}</CardDescription>
                </CardHeader>
              </Card>
            </motion.div>
          ))}
        </div>
      </section>

      <section id="features" className="border-y bg-muted/70 py-16 md:py-24">
        <div className="container">
          <SectionIntro
            eyebrow="What it does"
            title="A simple recorder for slide review behavior"
            text="Doctors can review normally while TimeStamp quietly records the moments that matter for the research team."
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

      <section id="session" className="container py-16 md:py-24">
        <SectionIntro
          eyebrow="During a session"
          title="Review slides the normal way"
          text="The extension is designed to stay out of the way while preserving a record for later review."
        />
        <div className="grid gap-4 lg:grid-cols-[0.95fr_1.05fr]">
          <Card>
            <CardHeader>
              <CardTitle>Doctor workflow</CardTitle>
              <CardDescription>
                These are the only actions a reviewer needs to remember.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ol className="space-y-4">
                {sessionSteps.map((step, index) => (
                  <li key={step} className="flex gap-3">
                    <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-primary text-sm font-bold text-white">
                      {index + 1}
                    </span>
                    <span className="text-sm leading-6 text-muted-foreground">{step}</span>
                  </li>
                ))}
              </ol>
            </CardContent>
          </Card>
          <Card className="bg-amber-soft/80">
            <CardHeader>
              <div className="mb-4 grid h-10 w-10 place-items-center rounded-lg bg-white text-amber-foreground">
                <ShieldCheck className="h-5 w-5" />
              </div>
              <CardTitle>What the research team receives</CardTitle>
              <CardDescription className="text-amber-foreground/80">
                TimeStamp creates review files that can be matched with the screen recording and
                transcript.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid gap-3 sm:grid-cols-2">
                {[
                  ["*_event.json", "Review events and slide position"],
                  ["*_cursor.json", "Optional cursor movement"],
                  ["*_video.mp4", "Screen recording"],
                  ["*_transcript.txt", "Searchable spoken notes"]
                ].map(([name, text]) => (
                  <div key={name} className="rounded-lg border bg-white p-4">
                    <Badge variant="amber" className="mb-3 font-mono">
                      {name}
                    </Badge>
                    <p className="text-sm text-muted-foreground">{text}</p>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>
      </section>

      <section id="help" className="border-y bg-[#1f2a2d] py-14 text-white">
        <div className="container grid gap-8 md:grid-cols-[1fr_auto] md:items-center">
          <div>
            <p className="mb-2 text-sm font-semibold text-amber">Need help?</p>
            <h2 className="text-3xl font-semibold tracking-tight">
              If the download does not start, ask the research coordinator for the TimeStamp .jar file.
            </h2>
          </div>
          <div className="flex flex-wrap gap-3">
            <Button asChild variant="outline" className="border-white/40 text-white hover:bg-white/10">
              <a href="https://github.com/nighthunter57/QuPath_TimeStamp_extension/releases">
                <ExternalLink className="h-4 w-4" />
                Extension releases
              </a>
            </Button>
            <Button asChild className="bg-white text-[#1f2a2d] hover:bg-white/90">
              <Link href="#downloads">
                <Download className="h-4 w-4" />
                Downloads
              </Link>
            </Button>
          </div>
        </div>
      </section>

      <footer className="container flex flex-col gap-3 py-8 text-sm text-muted-foreground md:flex-row md:items-center md:justify-between">
        <p>TimeStamp Extension for QuPath</p>
        <div className="flex items-center gap-2">
          <Boxes className="h-4 w-4" />
          Doctor-facing QuPath download and extension install page
        </div>
      </footer>
    </main>
  );
}
