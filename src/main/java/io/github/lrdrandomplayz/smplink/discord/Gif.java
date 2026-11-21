package io.github.lrdrandomplayz.smplink.discord;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** A tiny animated-GIF encoder for the bot's pixel-art icons. */
final class Gif {

    private Gif() {
    }

    /** Encodes the frames (delays in hundredths of a second) as a looping GIF with a transparent background. */
    static byte[] gif(List<int[][]> frames, List<Integer> delays) throws IOException {
        return gif(frames, delays, 1);
    }

    /** As above, each grid pixel drawn {@code scale} × {@code scale}. */
    static byte[] gif(List<int[][]> frames, List<Integer> delays, int scale) throws IOException {
        Map<Integer, Integer> palette = new LinkedHashMap<>();
        palette.put(0, 0);
        for (int[][] frame : frames) {
            for (int[] row : frame) {
                for (int argb : row) {
                    if (argb != 0 && !palette.containsKey(argb)) {
                        if (palette.size() >= 256) {
                            throw new IOException("too many colours for a GIF");
                        }
                        palette.put(argb, palette.size());
                    }
                }
            }
        }
        int size = palette.size() <= 2 ? 2 : Integer.highestOneBit(palette.size() - 1) << 1;
        byte[] r = new byte[size];
        byte[] gr = new byte[size];
        byte[] b = new byte[size];
        palette.forEach((argb, index) -> {
            int colour = argb;
            r[index] = (byte) (colour >> 16);
            gr[index] = (byte) (colour >> 8);
            b[index] = (byte) colour;
        });
        IndexColorModel colours = new IndexColorModel(8, size, r, gr, b, 0);

        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int f = 0; f < frames.size(); f++) {
                int[][] frame = frames.get(f);
                int width = frame[0].length * scale;
                int height = frame.length * scale;
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, colours);
                WritableRaster raster = image.getRaster();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        raster.setSample(x, y, 0, palette.get(frame[y / scale][x / scale]));
                    }
                }
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", "restoreToBackgroundColor");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "TRUE");
                control.setAttribute("delayTime", String.valueOf(delays.get(f)));
                control.setAttribute("transparentColorIndex", "0");
                // Without its own colour table the writer falls back to a default palette and the colours come out wrong.
                IIOMetadataNode table = child(root, "LocalColorTable");
                while (table.getLength() > 0) {
                    table.removeChild(table.getFirstChild());
                }
                table.setAttribute("sizeOfLocalColorTable", String.valueOf(size));
                table.setAttribute("sortFlag", "FALSE");
                for (int i = 0; i < size; i++) {
                    IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
                    entry.setAttribute("index", String.valueOf(i));
                    entry.setAttribute("red", String.valueOf(r[i] & 0xFF));
                    entry.setAttribute("green", String.valueOf(gr[i] & 0xFF));
                    entry.setAttribute("blue", String.valueOf(b[i] & 0xFF));
                    table.appendChild(entry);
                }
                if (f == 0) {
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, 0, 0});
                    child(root, "ApplicationExtensions").appendChild(app);
                }
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(image, null, metadata), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
