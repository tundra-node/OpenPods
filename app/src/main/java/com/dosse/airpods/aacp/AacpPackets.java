package com.dosse.airpods.aacp;

import java.nio.charset.StandardCharsets;

/**
 * AACP packet builders and parsers.
 * <p>
 * Received packet layout (all indices absolute):
 * <ul>
 * <li>bytes 0-3: header {@code 04 00 04 00}</li>
 * <li>byte 4: opcode</li>
 * <li>byte 5: usually {@code 00}</li>
 * <li>bytes 6+: payload, format depends on the opcode</li>
 * </ul>
 * Battery packets carry a component count at byte 6 followed by one 5 byte
 * tuple per component: {@code [component, 0x01, level, status, 0x01]}.
 * <p>
 * Protocol details derived from the LibrePods project (GPLv3).
 */
public class AacpPackets {

    private AacpPackets () {
    }

    // ---------------------------------------------------------------- packets

    /** The initial handshake. Note that it does not carry the usual header. */
    public static byte[] handshake () {
        return new byte[] {0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    }

    /** Enables the extended feature set (required for Pro 2 features). */
    public static byte[] setFeatureFlags () {
        return concat(AacpConstants.HEADER, new byte[] {0x4D, 0x00, (byte)0xD7, 0x00, 0x00, 0x00, 0x00, 0x00});
    }

    /** Subscribes to battery / ear detection / control command notifications. */
    public static byte[] requestNotifications (boolean earDetectionEnabled) {
        return concat(AacpConstants.HEADER, new byte[] {0x0F, 0x00, (byte)0xFF, (byte)0xFF, earDetectionEnabled ? (byte)0xFF : (byte)0xFD, (byte)0xFF});
    }

    /** Builds a control command with a value of up to 4 bytes. */
    public static byte[] controlCommand (int identifier, byte[] value) {
        byte[] payload = new byte[7];
        payload[0] = 0x09;
        payload[1] = 0x00;
        payload[2] = (byte)identifier;
        int n = Math.min(value == null ? 0 : value.length, 4);
        if (value != null)
            System.arraycopy(value, 0, payload, 3, n);
        return concat(AacpConstants.HEADER, payload);
    }

    /** Control command with a single byte value. */
    public static byte[] controlCommand (int identifier, byte value) {
        return controlCommand(identifier, new byte[] {value});
    }

    /** Control command with a boolean value (true is 0x01, false is 0x02). */
    public static byte[] controlCommand (int identifier, boolean value) {
        return controlCommand(identifier, new byte[] {value ? (byte)0x01 : (byte)0x02});
    }

    /** Proximity keys request (needed by some accessories before they answer). */
    public static byte[] requestProximityKeys (byte type) {
        return concat(AacpConstants.HEADER, new byte[] {0x30, 0x00, type, 0x00});
    }

    /** Renames the accessory (length-prefixed UTF-8 name). */
    public static byte[] rename (String newName) {
        byte[] name = newName.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[5 + name.length];
        payload[0] = 0x1A;
        payload[1] = 0x00;
        payload[2] = 0x01;
        payload[3] = (byte)name.length;
        payload[4] = 0x00;
        System.arraycopy(name, 0, payload, 5, name.length);
        return concat(AacpConstants.HEADER, payload);
    }

    // --------------------------------------------------------------- framing

    /**
     * Length in bytes of the packet starting at {@code offset}, or -1 if the
     * opcode is not recognized.
     */
    public static int packetLength (byte[] data, int offset, int length) {
        if (length - offset < 6)
            return -1;

        int opcode = data[offset + 4] & 0xFF;

        switch (opcode) {
            case AacpConstants.OP_BATTERY_INFO: {
                if (length - offset < 7)
                    return -1;
                int count = data[offset + 6] & 0xFF;
                if (count < 1 || count > 8)
                    return -1;
                return 6 + 1 + 5 * count;
            }
            case AacpConstants.OP_EAR_DETECTION:
                return 8;
            case AacpConstants.OP_CONTROL_COMMAND:
                return 11;
            case AacpConstants.OP_CONVERSATION_AWARENESS:
                return 10;
            default:
                return -1;
        }
    }

    public static boolean hasHeader (byte[] data, int offset, int length) {
        return length - offset >= 4 &&
                data[offset] == 0x04 && data[offset + 1] == 0x00 &&
                data[offset + 2] == 0x04 && data[offset + 3] == 0x00;
    }

    // ---------------------------------------------------------------- parsers

    /** Parsed battery packet payload. */
    public static class BatteryInfo {
        public boolean hasLeft, hasRight, hasCase;
        /** 0-100, or -1 when the component reports as disconnected. */
        public int leftPercent = -1, rightPercent = -1, casePercent = -1;
        public boolean leftCharging, rightCharging, caseCharging;
    }

    public static BatteryInfo parseBattery (byte[] data) {
        BatteryInfo info = new BatteryInfo();
        if (data == null || data.length < 7)
            return info;

        int count = data[6] & 0xFF;

        for (int i = 0; i < count; i++) {
            int base = 7 + i * 5;
            if (base + 3 >= data.length)
                break;

            int component = data[base] & 0xFF;
            int level = data[base + 2] & 0xFF;
            int status = data[base + 3] & 0xFF;

            boolean disconnected = status == AacpConstants.BATTERY_DISCONNECTED;
            boolean charging = status == AacpConstants.BATTERY_CHARGING || status == AacpConstants.BATTERY_OPTIMIZED_CHARGING;
            int percent = disconnected ? -1 : Math.max(0, Math.min(100, level));

            switch (component) {
                case AacpConstants.COMPONENT_LEFT:
                    info.hasLeft = true;
                    info.leftPercent = percent;
                    info.leftCharging = charging;
                    break;
                case AacpConstants.COMPONENT_RIGHT:
                    info.hasRight = true;
                    info.rightPercent = percent;
                    info.rightCharging = charging;
                    break;
                case AacpConstants.COMPONENT_CASE:
                    info.hasCase = true;
                    info.casePercent = percent;
                    info.caseCharging = charging;
                    break;
                default:
                    break;
            }
        }

        return info;
    }

    /** Parses the ear detection packet into {@code {primary, secondary}}. */
    public static int[] parseEarDetection (byte[] data) {
        if (data == null || data.length < 8)
            return null;
        return new int[] {data[6] & 0xFF, data[7] & 0xFF};
    }

    /** Number of reported components that are in an ear. */
    public static int inEarCount (int[] earDetection) {
        if (earDetection == null)
            return 0;

        int count = 0;
        for (int value : earDetection) {
            if (value == AacpConstants.EAR_IN_EAR)
                count++;
        }
        return count;
    }

    /** Parsed control command. */
    public static class ControlCommand {
        public final int identifier;
        public final byte[] value;

        public ControlCommand (int identifier, byte[] value) {
            this.identifier = identifier;
            this.value = value;
        }
    }

    public static ControlCommand parseControlCommand (byte[] data) {
        if (data == null || data.length < 11)
            return null;
        int identifier = data[6] & 0xFF;
        byte[] value = new byte[] {data[7], data[8], data[9], data[10]};
        return new ControlCommand(identifier, value);
    }

    /** Noise control mode from a control command, or -1 if not a valid mode. */
    public static int noiseControlMode (ControlCommand command) {
        if (command == null || command.identifier != AacpConstants.CC_LISTENING_MODE || command.value.length < 1)
            return -1;
        return command.value[0] & 0xFF;
    }

    /** Conversational awareness state (byte 9 of the packet). */
    public static boolean parseConversationAwareness (byte[] data) {
        return data != null && data.length >= 10 && data[9] != 0x00;
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] concat (byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    public static String toHex (byte[] data) {
        if (data == null)
            return "null";

        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

}
