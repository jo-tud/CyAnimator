package edu.ucsf.rbvi.CyAnimator.internal.video;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;

import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.NALUnit;
import org.jcodec.codecs.h264.io.model.NALUnitType;
import org.jcodec.common.NIOUtils;
import org.jcodec.common.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.Brand;
import org.jcodec.containers.mp4.MP4Packet;
import org.jcodec.containers.mp4.TrackType;
import org.jcodec.containers.mp4.muxer.FramesMP4MuxerTrack;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

import edu.ucsf.rbvi.CyAnimator.internal.model.TimeBase;

/**
 * This class was originally part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License.
 *
 * It has been modified by the RBVI to support the CyAnimator project to expose the FPS
 * parameter to provide user control of the frame rate
 * 
 * @author The JCodec project
 */
@Deprecated
public class MP4SequenceEncoder implements SequenceEncoder {
		public static Brand MP4 = Brand.MP4;
		public static Brand MOV = Brand.MOV;
    private SeekableByteChannel ch;
    private Picture toEncode;
    private Transform transform;
    private H264Encoder encoder;
    private ArrayList<ByteBuffer> spsList;
    private ArrayList<ByteBuffer> ppsList;
    private FramesMP4MuxerTrack outTrack;
    private ByteBuffer _out;
    private int frameNo;
    private int timeStamp;
    private MP4Muxer muxer;
    private ByteBuffer sps;
    private ByteBuffer pps;
		private TimeBase timebase;

    public MP4SequenceEncoder(File out, TimeBase timebase, Brand brand) throws IOException {
        this.ch = NIOUtils.writableFileChannel(out);
				this.timebase = timebase;

        // Muxer that will store the encoded frames
        muxer = new MP4Muxer(ch, brand);

        // Add video track to muxer
        outTrack = muxer.addTrack(TrackType.VIDEO, timebase.getTimeBase());

        // Allocate a buffer big enough to hold output frames
        _out = ByteBuffer.allocate(1920 * 1080 * 6);

        // Create an instance of encoder
        encoder = new H264Encoder();

        // Transform to convert between RGB and YUV
        transform = ColorUtil.getTransform(ColorSpace.RGB, encoder.getSupportedColorSpaces()[0]);

        // Encoder extra data ( SPS, PPS ) to be stored in a special place of
        // MP4
        spsList = new ArrayList<ByteBuffer>();
        ppsList = new ArrayList<ByteBuffer>();

				frameNo = 0;
				timeStamp = 0;

    }

		
		public void encodeImage(BufferedImage bi) throws IOException {
			try {
				Picture p = AWTUtil.fromBufferedImage(bi);
				encodeNativeFrame(p);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

    public void encodeNativeFrame(Picture pic) throws IOException {
        if (toEncode == null) {
            toEncode = Picture.create(pic.getWidth(), pic.getHeight(), encoder.getSupportedColorSpaces()[0]);
        }

        // Perform conversion
        transform.transform(pic, toEncode);

        // Encode image into H.264 frame, the result is stored in '_out' buffer
        _out.clear();
        ByteBuffer result = encoder.encodeFrame(toEncode, _out);

        // Based on the frame above form correct MP4 packet
        spsList.clear();
        ppsList.clear();
        H264Utils.wipePS(result, spsList, ppsList);
        NALUnit nu = NALUnit.read(NIOUtils.from(result.duplicate(), 4));
        H264Utils.encodeMOVPacket(result);

        // We presume there will be only one SPS/PPS pair for now
        if (sps == null && spsList.size() != 0)
            sps = spsList.get(0);
        if (pps == null && ppsList.size() != 0)
            pps = ppsList.get(0);

				// Create the packet
        MP4Packet packet = new MP4Packet(result, /* The packet we're encoding */
				                                 timeStamp, /* The presentation timestamp */
				                                 timebase.getTimeBase(), /* Our time base */
																				 timebase.getFrameDuration(), /* The duration of a frame */
																				 frameNo, /* The frame number */
																				 nu.type == NALUnitType.IDR_SLICE, /* True if this is an I-frame */
																				 null, /* Not used */
                                         timeStamp, /* The presentation timestamp again */
																				 0 /* Should always be 0 (sample entry) */);
        // Add packet to video track
        outTrack.addFrame(packet);

        timeStamp += timebase.getFrameDuration();
        frameNo++;
    }

    public H264Encoder getEncoder() {
        return encoder;
    }

    public void finish() throws IOException {
        if (sps == null || pps == null)
            throw new RuntimeException(
                    "Somehow the encoder didn't generate SPS/PPS pair, did you encode at least one frame?");
        // Push saved SPS/PPS to a special storage in MP4
        outTrack.addSampleEntry(H264Utils.createMOVSampleEntry(Arrays.asList(new ByteBuffer[] {sps}), 
				                                                       Arrays.asList(new ByteBuffer[] {pps}), 4));

        // Write MP4 header and finalize recording
        muxer.writeHeader();
        NIOUtils.closeQuietly(ch);
    }
}
