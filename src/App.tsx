import { useEffect, useMemo, useState } from 'react'

type Tone = 'natural' | 'formal' | 'casual' | 'simple' | 'business'

type TranslationResult = {
  detected_language: string
  source_language: string
  target_language: string
  tone: Tone
  translation: string
  transliteration: string
  alternatives: Array<{
    label: string
    text: string
  }>
  notes: string[]
  confidence: 'high' | 'medium' | 'low'
}

type BridgeStatus = {
  ok: boolean
  configured: boolean
  model: string
  cacheSize: number
}

type HistoryEntry = {
  id: string
  createdAt: string
  sourceText: string
  sourceLanguage: string
  targetLanguage: string
  tone: Tone
  result: TranslationResult
}

const bridgeUrl = 'http://127.0.0.1:8791'
const historyStorageKey = 'duddyTranslator.history'

const languages = [
  'Auto detect',
  'English',
  'Spanish',
  'Portuguese',
  'French',
  'German',
  'Italian',
  'Dutch',
  'Japanese',
  'Korean',
  'Chinese (Simplified)',
  'Arabic',
  'Hebrew',
  'Russian',
  'Hindi',
  'Greek',
  'Latin',
]

const targetLanguages = languages.filter((language) => language !== 'Auto detect')

const tones: Array<{ value: Tone; label: string }> = [
  { value: 'natural', label: 'Natural' },
  { value: 'formal', label: 'Formal' },
  { value: 'casual', label: 'Casual' },
  { value: 'simple', label: 'Simple' },
  { value: 'business', label: 'Business' },
]

const phrasePrompts = [
  'Good morning. How are you today?',
  'I would like to make an appointment.',
  'Can you explain this in simpler words?',
  'Thank you for your help. I appreciate it.',
  'Please translate this message accurately and politely.',
]

function loadHistory(): HistoryEntry[] {
  try {
    const saved = window.localStorage.getItem(historyStorageKey)
    return saved ? (JSON.parse(saved) as HistoryEntry[]) : []
  } catch {
    return []
  }
}

function confidenceLabel(confidence: TranslationResult['confidence']) {
  if (confidence === 'high') {
    return 'High confidence'
  }
  if (confidence === 'medium') {
    return 'Medium confidence'
  }
  return 'Low confidence'
}

function App() {
  const [sourceText, setSourceText] = useState('')
  const [sourceLanguage, setSourceLanguage] = useState('Auto detect')
  const [targetLanguage, setTargetLanguage] = useState('Spanish')
  const [tone, setTone] = useState<Tone>('natural')
  const [preserveFormatting, setPreserveFormatting] = useState(true)
  const [result, setResult] = useState<TranslationResult | null>(null)
  const [history, setHistory] = useState<HistoryEntry[]>(() => loadHistory())
  const [bridgeStatus, setBridgeStatus] = useState<BridgeStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const canTranslate = sourceText.trim().length > 0 && !busy
  const bridgeReady = Boolean(bridgeStatus?.configured)
  const characterCount = useMemo(() => sourceText.length.toLocaleString(), [sourceText])

  useEffect(() => {
    window.localStorage.setItem(historyStorageKey, JSON.stringify(history.slice(0, 12)))
  }, [history])

  useEffect(() => {
    let cancelled = false

    fetch(`${bridgeUrl}/health`)
      .then((response) => {
        if (!response.ok) {
          throw new Error('Bridge health check failed.')
        }
        return response.json() as Promise<BridgeStatus>
      })
      .then((status) => {
        if (!cancelled) {
          setBridgeStatus(status)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setBridgeStatus(null)
        }
      })

    return () => {
      cancelled = true
    }
  }, [])

  async function translate() {
    if (!canTranslate) {
      return
    }

    setBusy(true)
    setError('')

    try {
      const response = await fetch(`${bridgeUrl}/api/translate`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: sourceText,
          sourceLanguage,
          targetLanguage,
          tone,
          preserveFormatting,
        }),
      })

      if (!response.ok) {
        const payload = (await response.json().catch(() => null)) as { error?: string } | null
        throw new Error(payload?.error ?? 'Translation failed.')
      }

      const nextResult = (await response.json()) as TranslationResult
      setResult(nextResult)
      setHistory((current) => [
        {
          id: window.crypto.randomUUID(),
          createdAt: new Date().toISOString(),
          sourceText,
          sourceLanguage,
          targetLanguage,
          tone,
          result: nextResult,
        },
        ...current,
      ].slice(0, 12))
    } catch (translateError) {
      const message = translateError instanceof Error ? translateError.message : 'Translation failed.'
      setError(message)
    } finally {
      setBusy(false)
    }
  }

  function swapLanguages() {
    const detectedLanguage =
      result?.detected_language && result.detected_language !== 'Auto detect' ? result.detected_language : 'English'
    const nextTargetLanguage = sourceLanguage === 'Auto detect' ? detectedLanguage : sourceLanguage

    if (sourceLanguage === 'Auto detect') {
      setSourceLanguage(result?.target_language ?? targetLanguage)
    } else {
      setSourceLanguage(targetLanguage)
    }
    setTargetLanguage(nextTargetLanguage)
    if (result?.translation) {
      setSourceText(result.translation)
      setResult(null)
    }
  }

  function copyTranslation() {
    if (result?.translation) {
      void navigator.clipboard?.writeText(result.translation)
    }
  }

  function loadEntry(entry: HistoryEntry) {
    setSourceText(entry.sourceText)
    setSourceLanguage(entry.sourceLanguage)
    setTargetLanguage(entry.targetLanguage)
    setTone(entry.tone)
    setResult(entry.result)
    setError('')
  }

  function clearWorkspace() {
    setSourceText('')
    setResult(null)
    setError('')
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Duddy Labs</p>
          <h1>Duddy Translator</h1>
        </div>
        <div className={bridgeReady ? 'status-pill ready' : 'status-pill warning'}>
          <span>{bridgeReady ? 'Bridge Ready' : 'Bridge Offline'}</span>
          <strong>{bridgeStatus?.model ?? 'Local bridge'}</strong>
        </div>
      </header>

      <section className="workspace">
        <section className="translator-panel">
          <div className="control-grid">
            <label>
              From
              <select value={sourceLanguage} onChange={(event) => setSourceLanguage(event.target.value)}>
                {languages.map((language) => (
                  <option key={language} value={language}>
                    {language}
                  </option>
                ))}
              </select>
            </label>
            <button type="button" className="swap-button" onClick={swapLanguages}>
              Swap
            </button>
            <label>
              To
              <select value={targetLanguage} onChange={(event) => setTargetLanguage(event.target.value)}>
                {targetLanguages.map((language) => (
                  <option key={language} value={language}>
                    {language}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Tone
              <select value={tone} onChange={(event) => setTone(event.target.value as Tone)}>
                {tones.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="translation-grid">
            <div className="text-panel">
              <div className="panel-heading">
                <p className="panel-label">Source</p>
                <span>{characterCount} chars</span>
              </div>
              <textarea
                value={sourceText}
                onChange={(event) => setSourceText(event.target.value)}
                placeholder="Type or paste text to translate..."
              />
              <div className="source-actions">
                <label className="toggle-row">
                  <input
                    type="checkbox"
                    checked={preserveFormatting}
                    onChange={(event) => setPreserveFormatting(event.target.checked)}
                  />
                  Preserve formatting
                </label>
                <button type="button" onClick={clearWorkspace}>
                  Clear
                </button>
              </div>
            </div>

            <div className="text-panel output-panel">
              <div className="panel-heading">
                <p className="panel-label">Translation</p>
                <span>{result ? confidenceLabel(result.confidence) : 'Ready'}</span>
              </div>
              <div className="translation-output">
                {busy ? (
                  <p className="muted">Translating...</p>
                ) : result ? (
                  <p>{result.translation}</p>
                ) : (
                  <p className="muted">Your translation will appear here.</p>
                )}
              </div>
              <div className="source-actions">
                <button type="button" className="primary-action" onClick={translate} disabled={!canTranslate}>
                  {busy ? 'Translating' : 'Translate'}
                </button>
                <button type="button" onClick={copyTranslation} disabled={!result}>
                  Copy
                </button>
              </div>
            </div>
          </div>

          {error && (
            <div className="callout warning">
              <strong>Translation unavailable</strong>
              <p>{error}</p>
            </div>
          )}

          {result && (
            <div className="result-details">
              <div>
                <p className="panel-label">Detected</p>
                <strong>{result.detected_language}</strong>
              </div>
              <div>
                <p className="panel-label">Transliteration</p>
                <p>{result.transliteration || 'Not needed for this translation.'}</p>
              </div>
              <div>
                <p className="panel-label">Notes</p>
                {result.notes.length > 0 ? (
                  <ul>
                    {result.notes.map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                ) : (
                  <p>No special notes.</p>
                )}
              </div>
            </div>
          )}
        </section>

        <aside className="side-rail">
          <section className="side-section">
            <div className="panel-heading">
              <p className="panel-label">Quick Phrases</p>
              <span>{phrasePrompts.length}</span>
            </div>
            <div className="phrase-list">
              {phrasePrompts.map((phrase) => (
                <button type="button" key={phrase} onClick={() => setSourceText(phrase)}>
                  {phrase}
                </button>
              ))}
            </div>
          </section>

          <section className="side-section">
            <div className="panel-heading">
              <p className="panel-label">History</p>
              <span>{history.length}</span>
            </div>
            <div className="history-list">
              {history.length === 0 ? (
                <p className="empty-state">No translations yet.</p>
              ) : (
                history.map((entry) => (
                  <button type="button" key={entry.id} onClick={() => loadEntry(entry)}>
                    <strong>
                      {entry.sourceLanguage} to {entry.targetLanguage}
                    </strong>
                    <span>{entry.sourceText.slice(0, 86)}</span>
                  </button>
                ))
              )}
            </div>
          </section>

          <section className="side-section status-section">
            <p className="panel-label">Bridge</p>
            <h2>{bridgeReady ? 'Ready' : 'Needs attention'}</h2>
            <p>
              {bridgeReady
                ? `Connected with ${bridgeStatus?.model}. Cached translations: ${bridgeStatus?.cacheSize ?? 0}.`
                : 'Start from the desktop shortcut or run npm run bridge.'}
            </p>
          </section>
        </aside>
      </section>
    </main>
  )
}

export default App
