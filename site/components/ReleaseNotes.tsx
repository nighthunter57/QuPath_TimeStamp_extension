import {
  Bell,
  CheckCircle2,
  ExternalLink,
  GitCommitHorizontal,
  ShieldCheck
} from "lucide-react";

import { releaseNotices } from "@/data/releases";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

const RELEASES_URL =
  "https://github.com/nighthunter57/QuPath_TimeStamp_extension/releases";

export function ReleaseNotes() {
  return (
    <div className="space-y-5">
      {releaseNotices.map((release, index) => (
        <article
          key={release.version}
          className="overflow-hidden rounded-lg border border-border bg-card"
        >
          <div className="grid border-b border-border lg:grid-cols-[220px_1fr]">
            <div className="border-b border-border bg-muted/40 p-5 lg:border-b-0 lg:border-r">
              <div className="mb-4 flex items-center gap-2">
                <Badge variant={release.status === "Stable" ? "default" : "outline"}>
                  {release.status}
                </Badge>
                {index === 0 && (
                  <span className="text-xs font-semibold text-primary">Latest</span>
                )}
              </div>
              <p className="font-mono text-xl font-bold text-foreground">
                v{release.version}
              </p>
              <p className="mt-1 text-sm text-muted-foreground">{release.date}</p>
            </div>

            <div className="p-5 md:p-7">
              <h3 className="text-xl font-bold text-foreground">{release.title}</h3>
              <p className="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">
                {release.summary}
              </p>
            </div>
          </div>

          <div className="grid gap-7 p-5 md:p-7 lg:grid-cols-2">
            <div>
              <h4 className="mb-3 flex items-center gap-2 text-sm font-bold text-foreground">
                <GitCommitHorizontal className="h-4 w-4 text-primary" />
                Improvements
              </h4>
              <ul className="space-y-3">
                {release.improvements.map((item) => (
                  <li
                    key={item}
                    className="flex gap-3 text-sm leading-5 text-muted-foreground"
                  >
                    <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h4 className="mb-3 flex items-center gap-2 text-sm font-bold text-foreground">
                <ShieldCheck className="h-4 w-4 text-primary" />
                Fixes and validation
              </h4>
              <ul className="space-y-3">
                {release.fixes.map((item) => (
                  <li
                    key={item}
                    className="flex gap-3 text-sm leading-5 text-muted-foreground"
                  >
                    <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </article>
      ))}

      <div className="flex flex-col gap-3 rounded-lg border border-border bg-muted/30 p-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <Bell className="mt-0.5 h-5 w-5 shrink-0 text-primary" />
          <div>
            <p className="text-sm font-bold text-foreground">Release notifications</p>
            <p className="text-xs leading-5 text-muted-foreground">
              Published builds and technical release notes are kept in the GitHub
              release history.
            </p>
          </div>
        </div>
        <Button variant="outline" size="sm" asChild className="shrink-0 gap-2">
          <a href={RELEASES_URL} target="_blank" rel="noopener noreferrer">
            Release history
            <ExternalLink className="h-4 w-4" />
          </a>
        </Button>
      </div>
    </div>
  );
}
