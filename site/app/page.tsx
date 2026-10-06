"use client";

import Image from "next/image";
import { useState } from "react";
import {
  ArrowDown, ArrowRight, Check, CheckCircle2, ChevronDown, Download,
  ExternalLink, FileText, Laptop, MousePointer2, Pause, ShieldCheck,
} from "lucide-react";
import histologyBanner from "@/assets/histology-support-banner.jpg";
import { computers, doctorPackage, goodToKnow, helpItems, recorderSteps, studySignals, type Computer } from "@/data/install-guide";

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
          <nav className="desktop-nav" aria-label="Main navigation"><a href="#study">Study</a><a href="#install">Install</a><a href="#first-recording">Record</a><a href="#help">Help</a></nav>
          <a className="button button-small" href="#install">Install <ArrowRight size={16} aria-hidden="true" /></a>
        </div>
        <nav className="mobile-nav site-container" aria-label="Mobile navigation"><a href="#study">Study</a><a href="#install">Install</a><a href="#first-recording">Record</a><a href="#help">Help</a></nav>
      </header>

      <main id="main">
        <section id="top" className="hero site-container">
          <div className="hero-copy">
            <h1>Record your observations <span>while you work in QuPath.</span></h1>
            <p className="hero-description">For the diagnostic-process pilot study. TimeStamp records your dictation, mouse path and image actions together, all on your computer.</p>
            <div className="hero-actions"><a className="button" href="#install">Install TimeStamp <ArrowDown size={18} aria-hidden="true" /></a><a className="text-link" href={doctorPackage.guide} download><FileText size={16} aria-hidden="true" /> Printable guide (PDF)</a></div>
            <p className="hero-footnote">For QuPath 0.6 · Windows, Mac & Linux · Preview {doctorPackage.version}</p>
          </div>
          <RecorderPreview />
        </section>

        <section id="study" className="section site-container study-section">
          <div className="section-heading"><div><h2>About the study</h2><p>We are studying how physicians reach a diagnosis, step by step, not only the final result. For about 30 cases, TimeStamp records these signals together while you work as usual:</p></div></div>
          <ul className="study-signals">{studySignals.map(item => <li key={item.title}><strong>{item.title}</strong><span>{item.text}</span></li>)}</ul>
          <p className="study-more">Everything stays on your computer and is recorded only while TimeStamp shows Recording. <a className="text-link" href={doctorPackage.guide} download>Full study overview and guide (PDF)</a></p>
        </section>

        <section id="install" className="section site-container">
          <div className="section-heading"><div><h2>Install</h2><p>One time only. Choose your computer and follow the steps.</p></div></div>
          <div className="installation-layout">
            <aside className="before-panel">
              <div className="before-icon"><Laptop size={27} aria-hidden="true" /></div>
              <h3>You need</h3>
              <ul className="checklist">
                <li><Check size={18} aria-hidden="true" /><span><strong>QuPath 0.6.0</strong>Opened once, so its setup is complete.</span></li>
                <li><Check size={18} aria-hidden="true" /><span><strong>A supported computer</strong>Windows 10/11 (x64), Mac or Linux. Not Windows on ARM.</span></li>
                <li><Check size={18} aria-hidden="true" /><span><strong>Internet, once</strong>About 3.6 GB downloads during setup.</span></li>
                <li><Check size={18} aria-hidden="true" /><span><strong>A microphone</strong>Built-in or headset.</span></li>
                <li><Check size={18} aria-hidden="true" /><span><strong>Permission to install</strong>On a hospital computer, ask IT.</span></li>
              </ul>
              <a className="text-link" href={doctorPackage.guide} download><FileText size={17} aria-hidden="true" /> Printable guide (PDF)</a>
            </aside>
            <div className="installation-main">
              <fieldset className="computer-picker">
                <legend>Your computer</legend>
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
                  <li><span className="step-number">1</span><div><h3>Note your QuPath folder, then quit QuPath</h3><p>In QuPath, open Preferences and write down the <strong>User directory</strong>. Then quit QuPath.</p><p className="step-note">No QuPath yet? <a className="text-link" href={qupathDownload} target="_blank" rel="noopener noreferrer">Get QuPath 0.6.0 <ExternalLink size={15} aria-hidden="true" /><span className="sr-only"> (opens in a new tab)</span></a> {guide.qupath}</p></div></li>
                  <li><span className="step-number">2</span><div><h3>Download and unzip</h3><a className="button download-button" href={doctorPackage.href} download><Download size={18} aria-hidden="true" /> Download TimeStamp for {guide.name}</a><p>{guide.extract}</p></div></li>
                  <li><span className="step-number">3</span><div><h3>Run the installer</h3><p>{guide.run}</p><div className="installer-file"><FileText size={19} aria-hidden="true" /><code>{guide.installer}</code></div>{computer === "linux" && <pre className="terminal-command"><code>bash "Install TimeStamp on Linux.sh"</code></pre>}<p className="step-note">{guide.help}</p></div></li>
                  <li><span className="step-number">4</span><div><h3>Confirm your QuPath folder</h3><p>Press <strong>Enter</strong> if the folder shown matches your User directory. If not, paste your folder and press <strong>Enter</strong>.</p></div></li>
                  <li><span className="step-number">5</span><div><h3>Wait until it says</h3><div className="success-message"><CheckCircle2 size={19} aria-hidden="true" /><span>TimeStamp is ready for the doctor.</span></div><p>Keep the window open; the speech download takes the longest. When asked, say one sentence to test the microphone. Then open QuPath.</p></div></li>
                </ol>
                <div className="next-step"><span><strong>Installed? Make a 1-minute test recording.</strong><small>Use no patient information.</small></span><a href="#first-recording" className="circle-link" aria-label="Go to recording steps"><ArrowRight aria-hidden="true" /></a></div>
              </div>
            </div>
          </div>
        </section>

        <section id="first-recording" className="recording-section">
          <div className="site-container section">
            <div className="section-heading"><div><h2>Record a session</h2></div></div>
            <div className="workflow-summary" aria-label="Recording workflow"><span>Start</span><ArrowRight aria-hidden="true" /><span>Pause / Resume</span><ArrowRight aria-hidden="true" /><span>Finish &amp; review</span><ArrowRight aria-hidden="true" /><span>Save</span></div>
            <ol className="recording-grid">{recorderSteps.map((step, index) => <li key={step.title}><span className="recording-number">0{index + 1}</span><h3>{step.title}</h3><p>{step.text}</p></li>)}</ol>
            <div className="important-difference"><Pause size={22} aria-hidden="true" /><p><strong>One recording per case. Pause is a break; Finish &amp; review ends the case.</strong></p></div>
            <div className="privacy-items good-to-know">{goodToKnow.map(item => <article key={item.title}><ShieldCheck aria-hidden="true" /><div><h3>{item.title}</h3><p>{item.text}</p></div></article>)}</div>
          </div>
        </section>

        <section id="help" className="help-section section">
          <div className="site-container help-layout"><div><h2>Help</h2><p className="section-intro">On a hospital computer, IT can help with installing and permissions.</p><a className="text-link" href="mailto:haopham52d@gmail.com?subject=TimeStamp%20help">Email for help <ArrowRight size={17} aria-hidden="true" /></a><p className="support-note">In the recorder, choose More → Copy support information and paste it into your email. Never send patient information.</p></div><div className="faq-list">{helpItems.map(item => <details key={item.question}><summary>{item.question}<ChevronDown size={19} aria-hidden="true" /></summary><p>{item.answer}</p></details>)}</div></div>
        </section>
      </main>
      <footer className="site-container site-footer"><div className="brand"><span className="brand-mark">ts<span>.</span></span><span>TimeStamp<small>for QuPath</small></span></div><div><span>Preview {doctorPackage.version} · {doctorPackage.date}</span><a href={repository} target="_blank" rel="noopener noreferrer">Source <ExternalLink size={14} aria-hidden="true" /><span className="sr-only"> (opens in a new tab)</span></a></div></footer>
    </div>
  );
}
