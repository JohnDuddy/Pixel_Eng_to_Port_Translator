import assert from 'node:assert/strict'
import { createCacheKey, extractOutputText, sanitizeTranslationRequest } from './translate-server.mjs'

const sanitized = sanitizeTranslationRequest({
  text: `${'hello '.repeat(2000)}`,
  sourceLanguage: 'Auto detect',
  targetLanguage: 'Spanish',
  tone: 'formal',
  preserveFormatting: true,
})

assert.equal(sanitized.text.length, 8000)
assert.equal(sanitized.sourceLanguage, 'Auto detect')
assert.equal(sanitized.targetLanguage, 'Spanish')
assert.equal(sanitized.tone, 'formal')
assert.equal(sanitized.preserveFormatting, true)

const fallback = sanitizeTranslationRequest({
  text: 'Thank you',
  tone: 'pirate',
})

assert.equal(fallback.sourceLanguage, 'Auto detect')
assert.equal(fallback.targetLanguage, 'English')
assert.equal(fallback.tone, 'natural')

const keyA = createCacheKey({ model: 'test-model', sanitized })
const keyB = createCacheKey({ model: 'test-model', sanitized })
assert.equal(keyA, keyB)
assert.match(keyA, /^[a-f0-9]{64}$/)

assert.equal(extractOutputText({ output_text: '{"translation":"hola"}' }), '{"translation":"hola"}')
assert.equal(
  extractOutputText({
    output: [
      {
        content: [
          {
            text: '{"translation":"bonjour"}',
          },
        ],
      },
    ],
  }),
  '{"translation":"bonjour"}',
)

console.log('Duddy Translator bridge tests passed')
