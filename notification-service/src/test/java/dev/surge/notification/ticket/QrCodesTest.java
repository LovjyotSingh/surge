package dev.surge.notification.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QrCodesTest {

    static String decode(BufferedImage image) throws Exception {
        return new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }

    @Test
    void qrDecodesBackToTheExactText() throws Exception {
        String text = "surge:ticket:42:u-7";
        byte[] png = QrCodes.png(text, 256);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(decode(image)).isEqualTo(text);
    }
}
