import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Telsiz uygulama ikonunu cizer. Kaynak dosyasi yok; PNG burada uretiliyor. */
public class MakeIcon {

    static final Color BG_TOP    = new Color(0x16, 0x2A, 0x24);
    static final Color BG_BOTTOM = new Color(0x0B, 0x0F, 0x14);
    static final Color BODY      = new Color(0x3D, 0xDC, 0x84);
    static final Color BODY_DARK = new Color(0x24, 0xA5, 0x60);
    static final Color SCREEN    = new Color(0x0B, 0x11, 0x0E);
    static final Color WAVE      = new Color(0x7C, 0xF0, 0xB0);

    public static void main(String[] args) throws Exception {
        int S = Integer.parseInt(args[0]);
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double u = S / 100.0;  // yuzdelik birim

        // --- arka plan: yuvarlak kare, dikey gecisli ---
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, S, BG_BOTTOM));
        g.fill(new RoundRectangle2D.Double(0, 0, S, S, 24 * u, 24 * u));

        // hafif ic parlama
        g.setPaint(new RadialGradientPaint(
            new Point2D.Double(S * 0.35, S * 0.28), (float) (S * 0.6),
            new float[]{0f, 1f},
            new Color[]{new Color(0x3D, 0xDC, 0x84, 40), new Color(0x3D, 0xDC, 0x84, 0)}));
        g.fill(new RoundRectangle2D.Double(0, 0, S, S, 24 * u, 24 * u));

        // --- ses dalgalari (govdenin arkasindan cikiyor) ---
        g.setStroke(new BasicStroke((float) (3.4 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double cx = 62 * u, cy = 31 * u;
        g.setColor(new Color(WAVE.getRed(), WAVE.getGreen(), WAVE.getBlue(), 255));
        g.draw(new Arc2D.Double(cx - 11 * u, cy - 11 * u, 22 * u, 22 * u, 300, 120, Arc2D.OPEN));
        g.setColor(new Color(WAVE.getRed(), WAVE.getGreen(), WAVE.getBlue(), 165));
        g.draw(new Arc2D.Double(cx - 19 * u, cy - 19 * u, 38 * u, 38 * u, 300, 120, Arc2D.OPEN));

        // --- anten ---
        g.setColor(BODY);
        g.fill(new RoundRectangle2D.Double(53.5 * u, 13 * u, 5 * u, 26 * u, 3 * u, 3 * u));
        g.setColor(WAVE);
        g.fill(new Ellipse2D.Double(52.2 * u, 10.5 * u, 7.6 * u, 7.6 * u));

        // --- govde ---
        Shape body = new RoundRectangle2D.Double(29 * u, 36 * u, 42 * u, 51 * u, 9 * u, 9 * u);
        g.setPaint(new GradientPaint(0, (float) (36 * u), BODY, 0, (float) (87 * u), BODY_DARK));
        g.fill(body);

        // --- ekran ---
        g.setColor(SCREEN);
        g.fill(new RoundRectangle2D.Double(34.5 * u, 41.5 * u, 31 * u, 16 * u, 3.5 * u, 3.5 * u));
        // ekranda ses seviyesi cubuklari
        g.setColor(BODY);
        double[] hs = {4.5, 8, 11.5, 8, 5.5};
        for (int i = 0; i < hs.length; i++) {
            double h = hs[i] * u;
            double x = (38.5 + i * 5.1) * u;
            g.fill(new RoundRectangle2D.Double(x, 49.5 * u + (11.5 * u - h) / 2, 2.6 * u, h, 1.3 * u, 1.3 * u));
        }

        // --- hoparlor delikleri ---
        g.setColor(SCREEN);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                g.fill(new Ellipse2D.Double(
                    (35.5 + c * 8.0) * u, (63 + r * 6.8) * u, 4.0 * u, 4.0 * u));
            }
        }

        g.dispose();
        ImageIO.write(img, "png", new File(args[1]));
        System.out.println("ikon: " + args[1] + " (" + S + "x" + S + ")");
    }
}
