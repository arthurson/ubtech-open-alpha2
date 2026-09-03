package com.ubtechinc.alpha.hardware;

import java.io.ByteArrayOutputStream;

/**
 * 2026-09: 由 sdk-module/ubtechalpha2robot 的 DeveloperPacketData 搬入（脫離
 * Alpha2OpenSdk 的一部分）。Small append-only buffer for building the payload
 * of a serial command frame.
 *
 * <p>Multi-byte values are written big-endian (high byte first), which is the byte order
 * the robot's chest / head microcontrollers expect. The initial-capacity argument is only
 * a hint; the buffer grows as needed.
 *
 * <p>註：原版嘅 putBytes(byte[]) 從無 caller，已刪除。
 */
public class DeveloperPacketData {
   private final ByteArrayOutputStream buffer;

   public DeveloperPacketData(int initialCapacity) {
      this.buffer = new ByteArrayOutputStream(Math.max(initialCapacity, 0));
   }

   /** Append a single byte. */
   public void putByte(byte value) {
      this.buffer.write(value & 0xFF);
   }

   /** Append a 16-bit value, high byte first (big-endian). */
   public void putShort_(short value) {
      this.buffer.write((value >> 8) & 0xFF);
      this.buffer.write(value & 0xFF);
   }

   /** The bytes written so far. */
   public byte[] getBuffer() {
      return this.buffer.toByteArray();
   }
}
