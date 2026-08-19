/**
 * SYSTEM 13 TASK 13.3.a: frontend mirror of the backend's PiiPatterns
 * (server/src/main/java/com/recoverpro/server/service/safety/PiiPatterns.java) -- same category
 * list, same "same scrubbing discipline" the task requires, kept as a plain list of regexes here
 * rather than a shared package since the two runtimes can't share code directly.
 */
const PII_PATTERNS: RegExp[] = [
  /\b[6-9]\d{9}\b/g, // Indian mobile
  /\b\d{12}\b/g, // Aadhaar
  /\b[A-Z]{5}\d{4}[A-Z]\b/g, // PAN
  /\b[A-Z]{2}\d{2}\s?[A-Z]{4}\d{7}\b/g, // Passport
  /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g, // Email
  /\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b/g, // Card
  /\b\d{9,18}\b/g, // Account/IFSC
];

export function stripPii(text: string | null | undefined): string | null | undefined {
  if (text == null) return text;
  return PII_PATTERNS.reduce((result, pattern) => result.replace(pattern, '[REDACTED]'), text);
}
