function createWavB64(name, freq, durationMs, volume) {
    const sampleRate = 22050;
    const numSamples = Math.floor(sampleRate * durationMs / 1000);
    const dataSize = numSamples * 2;
    const fileSize = 44 + dataSize;
    const buffer = Buffer.alloc(fileSize);
    let offset = 0;
    buffer.write('RIFF', offset); offset += 4;
    buffer.writeUInt32LE(fileSize - 8, offset); offset += 4;
    buffer.write('WAVE', offset); offset += 4;
    buffer.write('fmt ', offset); offset += 4;
    buffer.writeUInt32LE(16, offset); offset += 4;
    buffer.writeUInt16LE(1, offset); offset += 2;
    buffer.writeUInt16LE(1, offset); offset += 2;
    buffer.writeUInt32LE(sampleRate, offset); offset += 4;
    buffer.writeUInt32LE(sampleRate * 2, offset); offset += 4;
    buffer.writeUInt16LE(2, offset); offset += 2;
    buffer.writeUInt16LE(16, offset); offset += 2;
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
    console.log('---' + name + '---');
    console.log(buffer.toString('base64'));
}
createWavB64('nav_move', 800, 30, 0.15);
createWavB64('nav_select', 1200, 50, 0.25);
createWavB64('nav_boundary', 300, 80, 0.10);
