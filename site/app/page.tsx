"use client";

import Image from "next/image";
import Link from "next/link";
import {
  ArrowRight,
  BadgeCheck,
  Bell,
  CheckCircle2,
  Clock3,
  Download,
  ExternalLink,
  FileText,
  Github,
  Headphones,
  MessageSquareText,
  Mic2,
  RefreshCw,
  ShieldCheck
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ReleaseNotes } from "@/components/ReleaseNotes";
import { SupportTicketForm } from "@/components/SupportTicketForm";
import { latestRelease } from "@/data/releases";
import histologySupportBanner from "@/assets/histology-support-banner.jpg";

const CATALOG_URL =
  "https://github.com/nighthunter57/QuPath_TimeStamp_extension";
const REPOSITORY_URL =
  "https://github.com/nighthunter57/QuPath_TimeStamp_extension";
const EXTENSION_DOWNLOAD = "./downloads/TimeStamp-0.1.0-SNAPSHOT.jar";

const navigation = [
  { label: "Updates", href: "#updates" },
  { label: "Support", href: "#support" },
  { label: "Install", href: "#install" }
];

const quickActions = [
  {
    icon: MessageSquareText,
    title: "Report a problem",
    text: "Send a structured support ticket without a GitHub account.",
    href: "#support"
  },
  {
    icon: Bell,
    title: "Review updates",
    text: "See what changed, what was fixed, and the current release status.",
    href: "#updates"
  },
  {
    icon: Download,
    title: "Get the preview",
    text: `Download TimeStamp ${latestRelease.version} for local evaluation.`,
    href: EXTENSION_DOWNLOAD,
    download: true
  }
];

export default function Home() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-50 border-b border-border bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link href="/" className="flex min-w-0 items-center gap-3">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-primary text-sm font-extrabold text-primary-foreground">
              TS
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-bold">TimeStamp Extension</span>
              <span className="block truncate text-[11px] text-muted-foreground">
                Support and release center
              </span>
            </span>
          </Link>

          <nav className="hidden items-center gap-6 md:flex" aria-label="Main navigation">
            {navigation.map((item) => (
              <a
                key={item.label}
                href={item.href}
                className="text-sm font-semibold text-muted-foreground transition-colors hover:text-foreground"
              >
                {item.label}
              </a>
            ))}
          </nav>

          <Button size="sm" asChild className="gap-2">
            <a href="#support">
              <Headphones className="h-4 w-4" />
              <span className="hidden sm:inline">Get support</span>
              <span className="sm:hidden">Support</span>
            </a>
          </Button>
        </div>
      </header>

      <main>
        <section className="border-b border-border bg-card">
          <div className="mx-auto grid max-w-6xl gap-8 px-4 py-10 sm:px-6 md:py-14 lg:grid-cols-[0.9fr_1.1fr] lg:items-center">
            <div className="max-w-xl">
              <div className="mb-5 flex flex-wrap items-center gap-2">
                <Badge variant="outline" className="gap-1.5 border-emerald-300 text-emerald-800">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-600" />
                  Support available
                </Badge>
                <Badge variant="outline">QuPath 0.6.0</Badge>
              </div>

              <h1 className="text-4xl font-extrabold leading-tight text-foreground sm:text-5xl">
                TimeStamp Extension
              </h1>
              <p className="mt-3 text-lg font-semibold text-primary">
                Support, updates, and installation in one place.
              </p>
              <p className="mt-4 max-w-lg text-sm leading-6 text-muted-foreground sm:text-base">
                Report transcript or timestamp problems, review each change, and
                install the latest build without searching through project files.
              </p>

              <div className="mt-7 flex flex-col gap-3 sm:flex-row">
                <Button asChild size="lg" className="gap-2">
                  <a href="#support">
                    <MessageSquareText className="h-5 w-5" />
                    Open a support ticket
                  </a>
                </Button>
                <Button asChild size="lg" variant="outline" className="gap-2">
                  <a href="#updates">
                    <FileText className="h-5 w-5" />
                    Read latest update
                  </a>
                </Button>
              </div>

              <div className="mt-7 grid grid-cols-2 gap-4 border-t border-border pt-5 text-sm">
                <div>
                  <p className="text-xs font-semibold uppercase text-muted-foreground">
                    Current build
                  </p>
                  <p className="mt-1 font-mono font-bold">v{latestRelease.version}</p>
                </div>
                <div>
                  <p className="text-xs font-semibold uppercase text-muted-foreground">
                    Updated
                  </p>
                  <p className="mt-1 font-bold">{latestRelease.date}</p>
                </div>
              </div>
            </div>

            <div className="relative aspect-[16/10] min-h-72 min-w-0 w-full overflow-hidden rounded-lg border border-border bg-muted">
              <Image
                src={histologySupportBanner}
                alt="Generic H&E-stained tissue field used as a non-diagnostic interface preview"
                fill
                priority
                className="object-cover"
                sizes="(max-width: 1024px) 100vw, 55vw"
              />
              <div className="absolute inset-0 bg-black/10" />

              <div className="absolute left-4 top-4 flex items-center gap-2 rounded-md border border-white/30 bg-black/75 px-3 py-2 text-xs text-white shadow-lg backdrop-blur">
                <span className="h-2 w-2 rounded-full bg-red-500" />
                <span className="font-bold">REC</span>
                <span className="font-mono text-white/80">00:14:28</span>
              </div>

              <div className="absolute inset-x-4 bottom-4 rounded-md border border-white/30 bg-black/80 p-4 text-white shadow-xl backdrop-blur">
                <div className="flex items-center justify-between gap-3">
                  <p className="flex items-center gap-2 text-xs font-bold uppercase text-emerald-300">
                    <Mic2 className="h-4 w-4" />
                    Live transcript
                  </p>
                  <span className="font-mono text-xs text-white/60">00:14:25</span>
                </div>
                <p className="mt-2 text-sm leading-5 text-white/90">
                  The glands show preserved architecture in this reviewed region.
                </p>
              </div>
            </div>
          </div>
        </section>

        <section aria-label="Quick actions" className="border-b border-border bg-muted/25">
          <div className="mx-auto grid max-w-6xl divide-y divide-border px-4 sm:px-6 md:grid-cols-3 md:divide-x md:divide-y-0">
            {quickActions.map((action) => {
              const Icon = action.icon;
              return (
                <a
                  key={action.title}
                  href={action.href}
                  download={action.download}
                  className="group flex min-h-32 gap-4 px-1 py-6 md:px-6 first:md:pl-0 last:md:pr-0"
                >
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border border-border bg-card text-primary">
                    <Icon className="h-5 w-5" />
                  </span>
                  <span>
                    <span className="flex items-center gap-2 text-sm font-bold text-foreground">
                      {action.title}
                      <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-1" />
                    </span>
                    <span className="mt-1 block text-xs leading-5 text-muted-foreground">
                      {action.text}
                    </span>
                  </span>
                </a>
              );
            })}
          </div>
        </section>

        <section id="updates" className="scroll-mt-20 border-b border-border py-14 md:py-20">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <div className="mb-8 grid gap-4 md:grid-cols-[1fr_420px] md:items-end">
              <div>
                <p className="mb-2 text-xs font-bold uppercase text-primary">
                  Product updates
                </p>
                <h2 className="text-3xl font-extrabold text-foreground">
                  What changed and what was fixed
                </h2>
              </div>
              <p className="text-sm leading-6 text-muted-foreground">
                Each notice separates user-visible improvements from fixes and
                validation work, so reviewers can decide when to update.
              </p>
            </div>
            <ReleaseNotes />
          </div>
        </section>

        <section id="support" className="scroll-mt-20 border-b border-border bg-muted/25 py-14 md:py-20">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <div className="mb-8 max-w-3xl">
              <p className="mb-2 text-xs font-bold uppercase text-primary">
                Support
              </p>
              <h2 className="text-3xl font-extrabold text-foreground">
                Send a problem report or short survey
              </h2>
              <p className="mt-3 text-sm leading-6 text-muted-foreground">
                The form gathers the version and environment details needed to
                investigate transcript, timestamp, installation, and usability issues.
              </p>
            </div>

            <div className="grid gap-8 lg:grid-cols-[1fr_310px] lg:items-start">
              <SupportTicketForm />

              <aside className="space-y-6 lg:sticky lg:top-24">
                <div className="border-l-2 border-primary pl-4">
                  <h3 className="text-sm font-bold text-foreground">
                    Helpful information
                  </h3>
                  <ul className="mt-3 space-y-3 text-sm leading-5 text-muted-foreground">
                    <li className="flex gap-2">
                      <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                      TimeStamp and QuPath version numbers
                    </li>
                    <li className="flex gap-2">
                      <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                      Approximate recording duration before the problem
                    </li>
                    <li className="flex gap-2">
                      <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                      Whether the live text, final transcript, or both were affected
                    </li>
                    <li className="flex gap-2">
                      <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                      Safe log files with all patient identifiers removed
                    </li>
                  </ul>
                </div>

                <div className="rounded-lg border border-border bg-card p-5">
                  <ShieldCheck className="h-6 w-6 text-primary" />
                  <h3 className="mt-3 text-sm font-bold">Clinical privacy</h3>
                  <p className="mt-2 text-xs leading-5 text-muted-foreground">
                    Support requests are for software troubleshooting only. Never
                    include protected health information or diagnostic content.
                  </p>
                </div>

                <div className="rounded-lg border border-border bg-card p-5">
                  <Clock3 className="h-6 w-6 text-primary" />
                  <h3 className="mt-3 text-sm font-bold">Ticket delivery</h3>
                  <p className="mt-2 text-xs leading-5 text-muted-foreground">
                    The completed request opens in the sender&apos;s email application.
                    No ticket content is stored on this website.
                  </p>
                </div>
              </aside>
            </div>
          </div>
        </section>

        <section id="install" className="scroll-mt-20 py-14 md:py-20">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <div className="grid gap-10 lg:grid-cols-2">
              <div>
                <p className="mb-2 text-xs font-bold uppercase text-primary">
                  One-time setup
                </p>
                <h2 className="text-3xl font-extrabold">Simple updates for clinical users</h2>
                <p className="mt-3 max-w-xl text-sm leading-6 text-muted-foreground">
                  Add the TimeStamp catalog once. Future published releases can then
                  be installed from QuPath&apos;s Extension Manager without manually
                  replacing JAR files.
                </p>

                <ol className="mt-7 space-y-5">
                  {[
                    "Open Extensions > Manage extensions > Manage extension catalogs.",
                    "Add the TimeStamp catalog repository shown here.",
                    "Install or update TimeStamp, then restart QuPath."
                  ].map((step, index) => (
                    <li key={step} className="flex gap-4">
                      <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
                        {index + 1}
                      </span>
                      <span className="pt-1 text-sm leading-5 text-foreground">{step}</span>
                    </li>
                  ))}
                </ol>
              </div>

              <div className="rounded-lg border border-border bg-card p-5 md:p-7">
                <div className="flex items-center justify-between gap-4">
                  <div>
                    <p className="text-xs font-bold uppercase text-muted-foreground">
                      Extension catalog
                    </p>
                    <p className="mt-2 break-all font-mono text-sm text-foreground">
                      {CATALOG_URL}
                    </p>
                  </div>
                  <BadgeCheck className="h-6 w-6 shrink-0 text-primary" />
                </div>

                <div className="mt-6 grid gap-3 sm:grid-cols-2">
                  <Button asChild className="gap-2">
                    <a href={EXTENSION_DOWNLOAD} download>
                      <Download className="h-4 w-4" />
                      Download preview
                    </a>
                  </Button>
                  <Button asChild variant="outline" className="gap-2">
                    <a href={REPOSITORY_URL} target="_blank" rel="noopener noreferrer">
                      <Github className="h-4 w-4" />
                      Repository
                    </a>
                  </Button>
                </div>

                <div className="mt-6 flex items-start gap-3 border-t border-border pt-5">
                  <RefreshCw className="mt-0.5 h-5 w-5 shrink-0 text-primary" />
                  <p className="text-xs leading-5 text-muted-foreground">
                    Extension updates are one-click installs, not silent background
                    updates. QuPath must be restarted after installing a new version.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-border bg-card py-8">
        <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 sm:px-6 md:flex-row md:items-center md:justify-between">
          <div>
            <p className="text-sm font-bold">TimeStamp Extension for QuPath</p>
            <p className="mt-1 text-xs text-muted-foreground">
              Live transcript and timestamp support for digital pathology workflows.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-5 text-xs font-semibold">
            <a href="#support" className="text-muted-foreground hover:text-foreground">
              Support
            </a>
            <a href="#updates" className="text-muted-foreground hover:text-foreground">
              Updates
            </a>
            <a
              href={REPOSITORY_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1.5 text-muted-foreground hover:text-foreground"
            >
              GitHub
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </div>
        </div>
      </footer>
    </div>
  );
}
