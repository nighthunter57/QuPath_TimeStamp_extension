"use client";

import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  Bug,
  CheckCircle2,
  Clipboard,
  Download,
  Lightbulb,
  Mail,
  Mic2,
  Send,
  Settings2
} from "lucide-react";

import { Button } from "@/components/ui/button";

const SUPPORT_EMAIL = "haopham52d@gmail.com";

const requestTypes = [
  { value: "Bug report", label: "Problem", icon: Bug },
  { value: "Transcript quality", label: "Transcript", icon: Mic2 },
  { value: "Installation or update", label: "Install / update", icon: Download },
  { value: "Feature request or feedback", label: "Idea / feedback", icon: Lightbulb }
];

function detectOperatingSystem() {
  if (typeof navigator === "undefined") return "";
  const source = `${navigator.platform} ${navigator.userAgent}`.toLowerCase();
  if (source.includes("win")) return "Windows";
  if (source.includes("mac")) return "macOS";
  if (source.includes("linux")) return "Linux";
  return "Other";
}

function createTicketId() {
  const date = new Date().toISOString().slice(0, 10).replaceAll("-", "");
  const suffix = Math.random().toString(36).slice(2, 6).toUpperCase();
  return `TS-${date}-${suffix}`;
}

export function SupportTicketForm() {
  const [requestType, setRequestType] = useState(requestTypes[0].value);
  const [severity, setSeverity] = useState("Normal");
  const [qupathVersion, setQuPathVersion] = useState("0.6.0");
  const [extensionVersion, setExtensionVersion] = useState("0.1.0-SNAPSHOT");
  const [operatingSystem, setOperatingSystem] = useState("");
  const [summary, setSummary] = useState("");
  const [details, setDetails] = useState("");
  const [steps, setSteps] = useState("");
  const [rating, setRating] = useState("Not provided");
  const [contactName, setContactName] = useState("");
  const [contactEmail, setContactEmail] = useState("");
  const [privacyConfirmed, setPrivacyConfirmed] = useState(false);
  const [status, setStatus] = useState<"idle" | "copied" | "opened">("idle");
  const ticketId = useMemo(createTicketId, []);

  useEffect(() => {
    setOperatingSystem(detectOperatingSystem());
  }, []);

  const ticketText = useMemo(
    () =>
      [
        "TimeStamp Extension Support Request",
        "",
        `Ticket ID: ${ticketId}`,
        `Request type: ${requestType}`,
        `Priority: ${severity}`,
        `QuPath version: ${qupathVersion}`,
        `TimeStamp version: ${extensionVersion || "Unknown"}`,
        `Operating system: ${operatingSystem || "Unknown"}`,
        `Experience rating: ${rating}`,
        `Contact name: ${contactName || "Not provided"}`,
        `Contact email: ${contactEmail || "Use sender address"}`,
        "",
        `Summary: ${summary}`,
        "",
        "What happened:",
        details,
        "",
        "Steps or context:",
        steps || "Not provided",
        "",
        "Privacy confirmation: No patient-identifying information included."
      ].join("\n"),
    [
      contactEmail,
      contactName,
      details,
      extensionVersion,
      operatingSystem,
      qupathVersion,
      rating,
      requestType,
      severity,
      steps,
      summary,
      ticketId
    ]
  );

  const copyTicket = async () => {
    if (!summary.trim() || !details.trim()) return;
    await navigator.clipboard.writeText(ticketText);
    setStatus("copied");
  };

  const submitTicket = (event: React.FormEvent) => {
    event.preventDefault();
    if (!privacyConfirmed) return;

    const subject = `[TimeStamp Support] ${requestType}: ${summary} (${ticketId})`;
    const mailto = `mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent(
      subject
    )}&body=${encodeURIComponent(ticketText)}`;

    setStatus("opened");
    window.location.href = mailto;
  };

  return (
    <form
      onSubmit={submitTicket}
      className="overflow-hidden rounded-lg border border-border bg-card"
    >
      <div className="border-b border-border bg-muted/30 px-5 py-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-md bg-primary/10 text-primary">
            <Settings2 className="h-5 w-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-foreground">New support ticket</h3>
            <p className="text-xs text-muted-foreground">Reference {ticketId}</p>
          </div>
        </div>
      </div>

      <div className="space-y-6 p-5 md:p-6">
        <fieldset>
          <legend className="mb-2 text-xs font-bold uppercase text-muted-foreground">
            Request type
          </legend>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {requestTypes.map((type) => {
              const Icon = type.icon;
              const selected = requestType === type.value;
              return (
                <button
                  key={type.value}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => setRequestType(type.value)}
                  className={`flex min-h-16 flex-col items-center justify-center gap-1 rounded-md border px-2 py-2 text-center text-xs font-semibold transition-colors ${
                    selected
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-input bg-background text-muted-foreground hover:border-primary/40 hover:text-foreground"
                  }`}
                >
                  <Icon className="h-4 w-4" />
                  <span>{type.label}</span>
                </button>
              );
            })}
          </div>
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-2">
          <label className="space-y-1.5 text-sm font-semibold text-foreground">
            Priority
            <select
              value={severity}
              onChange={(event) => setSeverity(event.target.value)}
              className="form-control"
            >
              <option>Normal</option>
              <option>Work interrupted</option>
              <option>Cannot use extension</option>
              <option>Question only</option>
            </select>
          </label>
          <label className="space-y-1.5 text-sm font-semibold text-foreground">
            Operating system
            <select
              value={operatingSystem}
              onChange={(event) => setOperatingSystem(event.target.value)}
              className="form-control"
              required
            >
              <option value="" disabled>
                Select operating system
              </option>
              <option>Windows</option>
              <option>macOS</option>
              <option>Linux</option>
              <option>Other</option>
            </select>
          </label>
          <label className="space-y-1.5 text-sm font-semibold text-foreground">
            QuPath version
            <select
              value={qupathVersion}
              onChange={(event) => setQuPathVersion(event.target.value)}
              className="form-control"
            >
              <option>0.6.0</option>
              <option>0.5.x</option>
              <option>0.7.x</option>
              <option>Unknown</option>
            </select>
          </label>
          <label className="space-y-1.5 text-sm font-semibold text-foreground">
            TimeStamp version
            <input
              value={extensionVersion}
              onChange={(event) => setExtensionVersion(event.target.value)}
              className="form-control"
              placeholder="For example, 0.1.0"
            />
          </label>
        </div>

        <label className="block space-y-1.5 text-sm font-semibold text-foreground">
          Short summary
          <input
            value={summary}
            onChange={(event) => setSummary(event.target.value)}
            className="form-control"
            placeholder="For example, transcript stops after several minutes"
            maxLength={120}
            required
          />
        </label>

        <label className="block space-y-1.5 text-sm font-semibold text-foreground">
          What happened?
          <textarea
            value={details}
            onChange={(event) => setDetails(event.target.value)}
            className="form-control min-h-28 resize-y"
            placeholder="Describe what you expected and what you observed."
            maxLength={1800}
            required
          />
        </label>

        <label className="block space-y-1.5 text-sm font-semibold text-foreground">
          Steps or context
          <textarea
            value={steps}
            onChange={(event) => setSteps(event.target.value)}
            className="form-control min-h-20 resize-y"
            placeholder="What were you doing immediately before the issue?"
            maxLength={1000}
          />
        </label>

        <fieldset>
          <legend className="mb-2 text-sm font-semibold text-foreground">
            Overall experience
          </legend>
          <div className="flex flex-wrap gap-2">
            {["1", "2", "3", "4", "5", "Not provided"].map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={rating === value}
                onClick={() => setRating(value)}
                className={`h-9 min-w-9 rounded-md border px-3 text-xs font-semibold ${
                  rating === value
                    ? "border-primary bg-primary text-primary-foreground"
                    : "border-input bg-background text-muted-foreground hover:text-foreground"
                }`}
              >
                {value === "Not provided" ? "Skip" : value}
              </button>
            ))}
          </div>
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-2">
          <label className="space-y-1.5 text-sm font-semibold text-foreground">
            Name
            <input
              value={contactName}
              onChange={(event) => setContactName(event.target.value)}
              className="form-control"
              autoComplete="name"
              placeholder="Optional"
            />
          </label>
          <label className="space-y-1.5 text-sm font-semibold text-foreground">
            Contact email
            <input
              type="email"
              value={contactEmail}
              onChange={(event) => setContactEmail(event.target.value)}
              className="form-control"
              autoComplete="email"
              placeholder="Optional"
            />
          </label>
        </div>

        <div className="rounded-md border border-amber-300 bg-amber-50 p-4 text-amber-950">
          <div className="flex items-start gap-3">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" />
            <div>
              <p className="text-sm font-bold">Protect patient privacy</p>
              <p className="mt-1 text-xs leading-5">
                Do not include names, dates of birth, medical record numbers, slide
                identifiers, screenshots with identifiers, or other protected health
                information.
              </p>
            </div>
          </div>
          <label className="mt-3 flex cursor-pointer items-start gap-2 text-xs font-semibold">
            <input
              type="checkbox"
              checked={privacyConfirmed}
              onChange={(event) => setPrivacyConfirmed(event.target.checked)}
              className="mt-0.5 h-4 w-4 accent-primary"
              required
            />
            <span>I confirm this request contains no patient-identifying information.</span>
          </label>
        </div>

        {status !== "idle" && (
          <div
            role="status"
            className="flex items-start gap-2 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900"
          >
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
            <span>
              {status === "copied"
                ? "Ticket details copied."
                : "Your email application should now show the prepared ticket. Review it, add any safe attachments, and send."}
            </span>
          </div>
        )}

        <div className="flex flex-col-reverse gap-2 border-t border-border pt-5 sm:flex-row sm:items-center sm:justify-between">
          <p className="flex items-center gap-2 text-xs text-muted-foreground">
            <Mail className="h-4 w-4" />
            Delivered by your email application
          </p>
          <div className="flex gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={copyTicket}
              disabled={!summary.trim() || !details.trim()}
              className="gap-2"
            >
              <Clipboard className="h-4 w-4" />
              Copy
            </Button>
            <Button
              type="submit"
              disabled={!privacyConfirmed}
              className="gap-2"
            >
              <Send className="h-4 w-4" />
              Send ticket
            </Button>
          </div>
        </div>
      </div>
    </form>
  );
}
