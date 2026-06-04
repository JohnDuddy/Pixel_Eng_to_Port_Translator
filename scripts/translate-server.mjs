import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const host = process.env.DUDDY_TRANSLATOR_HOST || '127.0.0.1'
const port = Number(process.env.DUDDY_TRANSLATOR_BRIDGE_PORT || 8791)
const model = process.env.DUDDY_TRANSLATOR_MODEL || 'gpt-4o-mini'
const cachePath = path.join(projectRoot, '.translator-cache', 'translation-cache.json')
const maxCacheEntries = 500
const allowedOrigins = new Set([
  'http://127.0.0.1:5190',
  'http://localhost:5190',
  'http://127.0.0.1:5191',
  'http://localhost:5191',
])

const tones = new Set(['natural', 'formal', 'casual', 'simple', 'business'])

const translationSchema = {
  type: 'object',
  additionalProperties: false,
  required: [
    'detected_language',
    'source_language',
    'target_language',
    'tone',
    'translation',
    'transliteration',
    'alternatives',
    'notes',
    'confidence',
  ],
  properties: {
    detected_language: { type: 'string' },
    source_language: { type: 'string' },
    target_language: { type: 'string' },
    tone: {
      type: 'string',
      enum: ['natural', 'formal', 'casual', 'simple', 'business'],
    },
    translation: { type: 'string' },
    transliteration: { type: 'string' },
    alternatives: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['label', 'text'],
        properties: {
          label: { type: 'string' },
          text: { type: 'string' },
        },
      },
    },
    notes: {
      type: 'array',
      items: { type: 'string' },
    },
    confidence: {
      type: 'string',
      enum: ['high', 'medium', 'low'],
    },
  },
}

const translatorInstructions = [
  'You are Duddy Translator, a careful translation assistant.',
  'Translate faithfully and naturally, preserving meaning, names, numbers, dates, and formatting when requested.',
  'If source language is Auto detect, identify the source language from the text.',
  'Do not add facts or opinions.',
  'If a phrase is ambiguous, translate the most likely meaning and explain the ambiguity briefly in notes.',
  'For non-Latin scripts, include a useful transliteration; otherwise return an empty string.',
  'Return only JSON that matches the schema.',
].join(' ')

function stringValue(value, fallback = '') {
  return typeof value === 'string' ? value.trim() : fallback
}

export function sanitizeTranslationRequest(request) {
  const text = stringValue(request?.text).slice(0, 8000)
  const sourceLanguage = stringValue(request?.sourceLanguage, 'Auto detect').slice(0, 80)
  const targetLanguage = stringValue(request?.targetLanguage, 'English').slice(0, 80)
  const tone = tones.has(request?.tone) ? request.tone : 'natural'

  return {
    text,
    sourceLanguage,
    targetLanguage,
    tone,
    preserveFormatting: Boolean(request?.preserveFormatting),
  }
}

export function createCacheKey(payload) {
  return createHash('sha256').update(JSON.stringify(payload)).digest('hex')
}

export function extractOutputText(payload) {
  if (typeof payload?.output_text === 'string') {
    return payload.output_text
  }

  for (const item of payload?.output ?? []) {
    for (const content of item?.content ?? []) {
      if (typeof content?.text === 'string') {
        return content.text
      }
    }
  }

  return ''
}

async function loadCache() {
  try {
    const text = await readFile(cachePath, 'utf8')
    return JSON.parse(text)
  } catch {
    return {}
  }
}

async function saveCache(cache) {
  const entries = Object.entries(cache)
    .sort(([, left], [, right]) => String(right.createdAt).localeCompare(String(left.createdAt)))
    .slice(0, maxCacheEntries)

  await mkdir(path.dirname(cachePath), { recursive: true })
  const tempPath = `${cachePath}.tmp`
  await writeFile(tempPath, `${JSON.stringify(Object.fromEntries(entries), null, 2)}\n`, 'utf8')
  await rename(tempPath, cachePath)
}

function readWindowsUserApiKey() {
  if (process.platform !== 'win32') {
    return ''
  }

  try {
    return execFileSync(
      'powershell.exe',
      ['-NoProfile', '-Command', '[Environment]::GetEnvironmentVariable("OPENAI_API_KEY", "User")'],
      { encoding: 'utf8', timeout: 2000, windowsHide: true },
    ).trim()
  } catch {
    return ''
  }
}

function getOpenAiApiKey() {
  return stringValue(process.env.OPENAI_API_KEY) || readWindowsUserApiKey()
}

function setCorsHeaders(request, response) {
  const origin = request.headers.origin
  if (origin && allowedOrigins.has(origin)) {
    response.setHeader('Access-Control-Allow-Origin', origin)
    response.setHeader('Vary', 'Origin')
  }
  response.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
  response.setHeader('Access-Control-Allow-Headers', 'Content-Type')
}

function sendJson(request, response, statusCode, payload) {
  setCorsHeaders(request, response)
  response.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(`${JSON.stringify(payload)}\n`)
}

async function readJsonBody(request) {
  const chunks = []
  let total = 0

  for await (const chunk of request) {
    total += chunk.length
    if (total > 96 * 1024) {
      throw new Error('Request body is too large.')
    }
    chunks.push(chunk)
  }

  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

async function requestOpenAi(apiKey, sanitizedRequest) {
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model,
      instructions: translatorInstructions,
      input: JSON.stringify(sanitizedRequest),
      max_output_tokens: 1200,
      text: {
        format: {
          type: 'json_schema',
          name: 'duddy_translation',
          strict: true,
          schema: translationSchema,
        },
      },
    }),
  })

  if (!response.ok) {
    throw new Error(`OpenAI API request failed with HTTP ${response.status}.`)
  }

  const payload = await response.json()
  const outputText = extractOutputText(payload)
  if (!outputText) {
    throw new Error('OpenAI returned an empty translation response.')
  }

  return JSON.parse(outputText)
}

async function handleTranslationRequest(request, response, cache) {
  const apiKey = getOpenAiApiKey()
  if (!apiKey) {
    sendJson(request, response, 503, {
      error: 'OPENAI_API_KEY is not configured. Set it in Windows, then restart Duddy Translator.',
    })
    return
  }

  const body = await readJsonBody(request)
  const sanitizedRequest = sanitizeTranslationRequest(body)
  if (!sanitizedRequest.text) {
    sendJson(request, response, 400, { error: 'Enter text to translate.' })
    return
  }

  const cacheKey = createCacheKey({ model, sanitizedRequest })
  const cached = cache[cacheKey]?.response
  if (cached) {
    sendJson(request, response, 200, cached)
    return
  }

  const translation = await requestOpenAi(apiKey, sanitizedRequest)
  cache[cacheKey] = {
    createdAt: new Date().toISOString(),
    response: translation,
  }
  await saveCache(cache)
  sendJson(request, response, 200, translation)
}

export async function startServer() {
  const cache = await loadCache()

  const server = createServer((request, response) => {
    void (async () => {
      try {
        if (request.method === 'OPTIONS') {
          setCorsHeaders(request, response)
          response.writeHead(204)
          response.end()
          return
        }

        if (request.method === 'GET' && request.url === '/health') {
          sendJson(request, response, 200, {
            ok: true,
            configured: Boolean(getOpenAiApiKey()),
            model,
            cacheSize: Object.keys(cache).length,
          })
          return
        }

        if (request.method === 'POST' && request.url === '/api/translate') {
          await handleTranslationRequest(request, response, cache)
          return
        }

        sendJson(request, response, 404, { error: 'Not found.' })
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Duddy Translator bridge failed.'
        sendJson(request, response, 500, { error: message })
      }
    })()
  })

  await new Promise((resolve) => {
    server.listen(port, host, resolve)
  })

  console.log(`Duddy Translator bridge listening on http://${host}:${port}`)
  console.log(`OpenAI key configured: ${getOpenAiApiKey() ? 'yes' : 'no'}`)
  return server
}

const invokedPath = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : ''
if (import.meta.url === invokedPath) {
  await startServer()
}
