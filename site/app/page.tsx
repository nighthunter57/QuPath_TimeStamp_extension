"use client";

import Image from "next/image";
import { useState } from "react";
import {
  ArrowDown, ArrowRight, Check, CheckCircle2, ChevronDown, Download,
  ExternalLink, FileText, Headphones, Laptop, Mic2, MousePointer2,
  Pause, Printer, ShieldCheck,
} from "lucide-react";
import histologyBanner from "@/assets/histology-support-banner.jpg";
import { computers, doctorPackage, helpItems, recorderSteps, type Computer } from "@/data/install-guide";

const repository = "https://github.com/nighthunter57/QuPath_TimeStamp_extension";
const qupathDownload = "https://github.com/qupath/qupath/releases/tag/v0.6.0";

function RecorderPreview() {
  return (
    <figure className="recorder-preview">
      <div className="preview-top"><span className="preview-dots" aria-hidden="true"><i /><i /><i /></span><span>QuPath + TimeStamp</span><span className="preview-tag">Illustration</span></div>
      <div className="preview-content">
        <div className="slide-preview">
          <Image src={histologyBanner} alt="Example tissue image used to illustrate a QuPath session" fill priority sizes="(max-width: 760px) 40vw, 270px" className="slide-image" />
          <span className="slide-label">Example slide</span>
          <MousePointer2 className="slide-pointer" aria-hidden="true" />
        </div>
        <div className="preview-panel">
          <div className="preview-recording"><span className="record-dot" /> Recording <span>00:24</span></div>
          <div className="preview-controls" aria-label="Illustrated recorder controls, not interactive"><span><Pause size={13} aria-hidden="true" /> Pause</span><span>Finish &amp; review</span></div>
          <div className="preview-transcript"><p className="mini-label">Transcript · live preview</p><p>Let’s take a closer look at this area.</p><p className="preview-pending">Moving to the next field…</p></div>
          <div className="preview-events"><p className="mini-label">Recorded actions</p><p><span>00:18</span> Zoom changed</p><p><span>00:22</span> View moved</p></div>
        </div>
      </div>
      <figcaption><CheckCircle2 size={17} aria-hidden="true" /> Your voice, transcript, and image actions—in one session.</figcaption>
    </figure>
  );
}

export default function Home() {
  const [computer, setComputer] = useState<Computer>("windows");
  const guide = computers[computer];

  return (
    <div>
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="site-header">
        <div className="site-container header-inner">
          <a href="#top" className="brand" aria-label="TimeStamp for QuPath home"><span className="brand-mark">ts<span>.</span></span><span>TimeStamp<small>for QuPath</small></span></a>
          <nav className="desktop-nav" aria-label="Main navigation"><a href="#install">Installation</a><a href="#first-recording">First recording</a><a href="#help">Help</a></nav>
          <a className="button button-small" href="#install">Get started <ArrowRight size={16} aria-hidden="true" /></a>
        </div>
        <nav className="mobile-nav site-container" aria-label="Mobile navigation"><a href="#install">Installation</a><a href="#first-recording">First recording</a><a href="#help">Help</a></nav>
      </header>

      <main id="main">
        <section id="top" className="hero site-container">
          <div className="hero-copy">
            <p className="eyebrow"><span className="eyebrow-line" /> A simpler way to record your work</p>
            <h1>Your observations.<br /><span>One recorded session.</span></h1>
            <p className="hero-description">Speak while you work in QuPath. TimeStamp keeps your audio, transcript, and image actions together—ready for you to review.</p>
            <div className="hero-actions"><a className="button" href="#install">Install TimeStamp <ArrowDown size={18} aria-hidden="true" /></a><a className="text-link" href="#first-recording">Already installed? Start here <ArrowRight size={16} aria-hidden="true" /></a></div>
            <p className="hero-footnote">For QuPath 0.6 · Windows, Mac & Linux guides</p>
          </div>
          <RecorderPreview />
        </section>

        <div className="benefits-band"><div className="site-container benefits"><span><Mic2 aria-hidden="true" /> Record your voice</span><span><MousePointer2 aria-hidden="true" /> Keep image actions in view</span><span><FileText aria-hidden="true" /> Review before saving</span><span><ShieldCheck aria-hidden="true" /> Transcribe on your computer</span></div></div>

        <section id="install" className="section site-container">
          <div className="section-heading"><div><p className="eyebrow">01 / One-time setup</p><h2>Let’s get you set up.</h2><p>Choose your computer. Follow the steps below in order.</p></div><button type="button" className="print-button" onClick={() => window.print()}><Printer size={17} aria-hidden="true" /> Print this guide</button></div>
          <div className="installation-layout">
            <aside className="before-panel">
              <div className="before-icon"><Laptop size={27} aria-hidden="true" /></div>
              <h3>Before you begin</h3>
              <ul className="checklist">
                <li><Check size={18} aria-hidden="true" /><span><strong>Internet for setup</strong>About 3.6 GB of speech models download once. Ideally, IT or a colleague completes setup and a test recording before your first session.</span></li>
                <li><Check size={18} aria-hidden="true" /><span><strong>A working microphone</strong>A built-in or connected microphone is fine. Test it before recording.</span></li>
                <li><Check size={18} aria-hidden="true" /><span><strong>Permission to install</strong>On a hospital computer, check with IT first.</span></li>
              </ul>
              <div className="before-note"><strong>No separate Python setup.</strong><p>The TimeStamp installer takes care of its recording tools and default speech models.</p></div>
              <a className="text-link" href={doctorPackage.instructions} download><FileText size={17} aria-hidden="true" /> Download written instructions</a>
            </aside>
            <div className="installation-main">
              <fieldset className="computer-picker">
                <legend>Which computer are you using?</legend>
                <div className="computer-options">{(Object.keys(computers) as Computer[]).map((key) => (
                  <label key={key} className={computer === key ? "computer-option selected" : "computer-option"}>
                    <input type="radio" name="computer" value={key} checked={computer === key} onChange={() => setComputer(key)} aria-controls="computer-guide" />
                    <span><strong>{computers[key].name}</strong><small>{computers[key].detail}</small></span>
                    {computer === key && <CheckCircle2 size={18} aria-hidden="true" />}
                  </label>
                ))}</div>
              </fieldset>
              <div id="computer-guide" className="computer-guide">
                <p className="guide-label" role="status">Installation guide for {guide.name}</p>
                <ol className="install-steps">
                  <li><span className="step-number">1</span><div><h3>Install QuPath 0.6 first</h3><p>{guide.qupath}</p><a className="text-link" href={qupathDownload} target="_blank" rel="noopener noreferrer">Get QuPath 0.6.0 <ExternalLink size={15} aria-hidden="true" /><span className="sr-only"> (opens in a new tab)</span></a><p className="step-note">Whether QuPath is new or already installed: open it once, finish its user-folder setup using the recommended default, then close QuPath completely before continuing.</p></div></li>
                  <li><span className="step-number">2</span><div><h3>Download and extract TimeStamp</h3><p>{guide.extract}</p><a className="button download-button" href={doctorPackage.href} download><Download size={18} aria-hidden="true" /> Download TimeStamp for {guide.name}</a><p className="download-note">Preview {doctorPackage.version} · {doctorPackage.date}<br />One ZIP contains installers for all three systems.</p></div></li>
                  <li><span className="step-number">3</span><div><h3>Run the installer</h3><p>{guide.run}</p><div className="installer-file"><FileText size={19} aria-hidden="true" /><code>{guide.installer}</code></div>{computer === "linux" && <pre className="terminal-command"><code>bash "Install TimeStamp on Linux.sh"</code></pre>}<p className="step-note">{guide.help}</p><p>The installer briefly tests your microphone. Use only non-identifying test speech.</p></div></li>
                  <li><span className="step-number">4</span><div><h3>Wait for setup to finish</h3><p>Keep your computer connected to the internet. Look for this message before closing the setup window:</p><div className="success-message"><CheckCircle2 size={19} aria-hidden="true" /><span>TimeStamp is ready for the doctor.</span></div><p>Then reopen QuPath and allow microphone access when asked. If setup reported a microphone warning, resolve it before recording.</p></div></li>
                </ol>
                <div className="next-step"><span><strong>Installed? Try a short recording first.</strong><small>Confirm the microphone, Pause/Resume, and Save all work.</small></span><a href="#first-recording" className="circle-link" aria-label="Go to your first recording"><ArrowRight aria-hidden="true" /></a></div>
              </div>
            </div>
          </div>
          <div className="preview-notice"><ShieldCheck size={21} aria-hidden="true" /><p><strong>A preview for supervised evaluation.</strong> Not yet clinically validated. Real-device and Windows/Linux acceptance testing remain. Review every transcript and follow your organization’s approval process.</p></div>
        </section>

        <section id="first-recording" className="recording-section">
          <div className="site-container section">
            <div className="section-heading"><div><p className="eyebrow">02 / Your first recording</p><h2>Try it once. Then make it your routine.</h2><p>Use a short test with no patient information before your first real session.</p></div></div>
            <div className="workflow-summary" aria-label="Recording workflow"><span>Start</span><ArrowRight aria-hidden="true" /><span>Pause / Resume</span><ArrowRight aria-hidden="true" /><span>Finish &amp; review</span><ArrowRight aria-hidden="true" /><span>Save</span></div>
            <ol className="recording-grid">{recorderSteps.map((step, index) => <li key={step.title}><span className="recording-number">0{index + 1}</span><h3>{step.title}</h3><p>{step.text}</p></li>)}</ol>
            <div className="important-difference"><Pause size={22} aria-hidden="true" /><p><strong>Pause is a break. Finish &amp; review ends the recording.</strong><br />Use Resume to continue the same session. Choose Finish &amp; review only when you are ready for the final transcript. Need to add more afterwards? Record more continues the same take.</p></div>
          </div>
        </section>

        <section id="privacy" className="section site-container privacy-layout">
          <div><p className="eyebrow">03 / Know where your data goes</p><h2>Local recording.<br />Thoughtful review.</h2><p className="section-intro">TimeStamp helps you capture your work. You stay responsible for checking the text and protecting the recording.</p></div>
          <div className="privacy-items">
            <article><ShieldCheck aria-hidden="true" /><div><h3>Your audio stays on this computer</h3><p>Transcription runs locally after the required models are installed. Audio is not sent to a transcription service.</p></div></article>
            <article><Headphones aria-hidden="true" /><div><h3>Live text is a preview—not a clinical record</h3><p>Words can change or be missed. After Finish &amp; review, check the final text against the audio before using or sharing it.</p></div></article>
            <article><FileText aria-hidden="true" /><div><h3>Saving and deleting are different</h3><p>Working audio stays in your QuPath user folder under <code>timestamp/recordings</code>, even when you exclude it from an export. Declining to save when you close QuPath does not erase it. No automatic deletion or application-level encryption is configured.</p></div></article>
          </div>
        </section>

        <section id="help" className="help-section section">
          <div className="site-container help-layout"><div><p className="eyebrow">A little help, if you need it</p><h2>Stuck on a step?</h2><p className="section-intro">Start with these common questions. For managed computers, your IT team can help with installation and permissions.</p><a className="text-link" href="mailto:haopham52d@gmail.com?subject=TimeStamp%20installation%20help">Email for help <ArrowRight size={17} aria-hidden="true" /></a><p className="support-note">Include your operating system, QuPath version, TimeStamp version, and the step that failed. Do not send recordings, transcripts, or screenshots containing patient information.</p></div><div className="faq-list">{helpItems.map(item => <details key={item.question}><summary>{item.question}<ChevronDown size={19} aria-hidden="true" /></summary><p>{item.answer}</p></details>)}</div></div>
        </section>
      </main>
      <footer className="site-container site-footer"><div className="brand"><span className="brand-mark">ts<span>.</span></span><span>TimeStamp<small>Record. Review. Keep together.</small></span></div><div><span>Preview · Updated {doctorPackage.date}</span><a href={repository} target="_blank" rel="noopener noreferrer">Project & source <ExternalLink size={14} aria-hidden="true" /><span className="sr-only"> (opens in a new tab)</span></a></div></footer>
    </div>
  );
}
