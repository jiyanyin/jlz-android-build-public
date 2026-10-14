// The public CI only uploads authenticated ciphertext. Signing stays in android-build.yml.
import fs from 'node:fs';
import crypto from 'node:crypto';
const [mode, input, output] = process.argv.slice(2);
const key = Buffer.from(process.env.WB_PRIVATE_BUILD_KEY || '', 'base64');
if (key.length !== 32) throw new Error('private build key missing');
const aad = Buffer.from('world-between-private-build-v1');
if (mode === 'decrypt') {
  const data = fs.readFileSync(input);
  const cipher = crypto.createDecipheriv('aes-256-gcm', key, data.subarray(0, 12));
  cipher.setAAD(aad); cipher.setAuthTag(data.subarray(-16));
  fs.writeFileSync(output, Buffer.concat([cipher.update(data.subarray(12, -16)), cipher.final()]));
} else if (mode === 'encrypt') {
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce); cipher.setAAD(aad);
  fs.writeFileSync(output, Buffer.concat([nonce, cipher.update(fs.readFileSync(input)), cipher.final(), cipher.getAuthTag()]));
} else throw new Error('unknown envelope operation');
