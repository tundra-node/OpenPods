package com.dosse.airpods.aacp;

/**
 * Constants for the Apple Accessory Configuration Protocol (AACP).
 * <p>
 * AACP runs over an L2CAP connection-oriented channel on PSM 0x1001 of the
 * accessory's AACP service UUID. The protocol details here were derived from
 * the LibrePods project (GPLv3), which reverse engineered the packets.
 */
public class AacpConstants {

    /** L2CAP PSM used for AACP. */
    public static final int PSM = 0x1001;

    /** AACP service UUID advertised by AirPods. */
    public static final String AACP_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a";

    /** Every packet (except the handshake) starts with this header. */
    public static final byte[] HEADER = {0x04, 0x00, 0x04, 0x00};

    /** Packet opcodes (byte 4 of a received packet). */
    public static final int OP_BATTERY_INFO = 0x04;
    public static final int OP_EAR_DETECTION = 0x06;
    public static final int OP_CONTROL_COMMAND = 0x09;
    public static final int OP_REQUEST_NOTIFICATIONS = 0x0F;
    public static final int OP_RENAME = 0x1A;
    public static final int OP_INFORMATION = 0x1D;
    public static final int OP_CONVERSATION_AWARENESS = 0x4B;
    public static final int OP_SET_FEATURE_FLAGS = 0x4D;

    /** Battery packet component identifiers. */
    public static final int COMPONENT_LEFT = 4;
    public static final int COMPONENT_RIGHT = 2;
    public static final int COMPONENT_CASE = 8;

    /** Battery packet status values. */
    public static final int BATTERY_CHARGING = 1;
    public static final int BATTERY_NOT_CHARGING = 2;
    public static final int BATTERY_DISCONNECTED = 4;
    public static final int BATTERY_OPTIMIZED_CHARGING = 5;

    /** Ear detection values (in the ear detection packet). */
    public static final int EAR_IN_EAR = 0;
    public static final int EAR_OUT = 1;
    public static final int EAR_IN_CASE = 2;

    /** Noise control modes (value of the LISTENING_MODE control command). */
    public static final int NOISE_OFF = 1;
    public static final int NOISE_ANC = 2;
    public static final int NOISE_TRANSPARENCY = 3;
    public static final int NOISE_ADAPTIVE = 4;

    /** Control command identifiers. */
    public static final int CC_OWNS_CONNECTION = 0x06;
    public static final int CC_EAR_DETECTION_CONFIG = 0x0A;
    public static final int CC_LISTENING_MODE = 0x0D;
    public static final int CC_CONVERSATION_DETECT = 0x28;
    public static final int CC_AUTO_ANC_STRENGTH = 0x2E;
    public static final int CC_ALLOW_OFF_OPTION = 0x34;

    private AacpConstants () {
    }

}
