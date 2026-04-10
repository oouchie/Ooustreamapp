const fs = require('fs');
const path = require('path');

function createWav(filename, freq, durationMs, volume) {
    const sampleRate = 22050;
    const numSamples = Math.floor(sampleRate * durationMs / 1000);
    const dataSize = numSamples * 2; // 16-bit mono
    const fileSize = 44 + dataSize;

    const buffer = Buffer.alloc(fileSize);
    let offset = 0;

    // RIFF header
    buffer.write('RIFF', offset); offset += 4;
    buffer.writeUInt32LE(fileSize - 8, offset); offset += 4;
    buffer.write('WAVE', offset); offset += 4;

    // fmt chunk
    buffer.write('fmt ', offset); offset += 4;
    buffer.writeUInt32LE(16, offset); offset += 4; // chunk size
    buffer.writeUInt16LE(1, offset); offset += 2;  // PCM
    buffer.writeUInt16LE(1, offset); offset += 2;  // mono
    buffer.writeUInt32LE(sampleRate, offset); offset += 4;
    buffer.writeUInt32LE(sampleRate * 2, offset); offset += 4; // byte rate
    buffer.writeUInt16LE(2, offset); offset += 2;  // block align
    buffer.writeUInt16LE(16, offset); offset += 2; // bits per sample

    // data chunk
    buffer.write('data', offset); offset += 4;
    buffer.writeUInt32LE(dataSize, offset); offset += 4;

    const fadeSamples = Math.floor(sampleRate * 0.01);

    for (let i = 0; i < numSamples; i++) {
        const t = i / sampleRate;
        const fadeIn = Math.min(1.0, i / Math.max(1, fadeSamples));
        const fadeOut = Math.min(1.0, (numSamples - i) / Math.max(1, fadeSamples));
        const env = fadeIn * fadeOut;
        let sample = Math.floor(32767 * volume * env * Math.sin(2 * Math.PI * freq * t));
        sample = Math.max(-32767, Math.min(32767, sample));
        buffer.writeInt16LE(sample, offset);
        offset += 2;
    }

    fs.writeFileSync(filename, buffer);
    console.log('Created: ' + filename);
}

const rawDir = path.join(__dirname, 'app', 'src', 'main', 'res', 'raw');

createWav(path.join(rawDir, 'nav_move.wav'), 800, 30, 0.15);
createWav(path.join(rawDir, 'nav_select.wav'), 1200, 50, 0.25);
createWav(path.join(rawDir, 'nav_boundary.wav'), 300, 80, 0.10);

console.log('All sound files generated!');
