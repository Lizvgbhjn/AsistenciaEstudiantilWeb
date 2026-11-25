package ec.asistencia;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.opencv.core.Core;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

final class FaceId {
    private static final String YUNET = "modelos/face_detection_yunet_2023mar.onnx";
    private static final String SFACE = "modelos/face_recognition_sface_2021dec.onnx";
    private static boolean cargado;
    private static FaceDetectorYN detector;
    private static FaceRecognizerSF reconocedor;

    private FaceId() {}

    static synchronized byte[] descriptor(BufferedImage imagen) throws Exception {
        cargar();
        Path temporal = Files.createTempFile("asistencia-rostro-", ".jpg");
        try {
            ImageIO.write(imagen, "jpg", temporal.toFile());
            var descriptor = ReconocimientoFacial.obtenerDescriptor(temporal.toString(), detector, reconocedor);
            try { return DescriptorFacial.convertirABytes(descriptor); }
            finally { descriptor.release(); }
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    /** Estima giro horizontal usando las cinco marcas faciales de YuNet. */
    static synchronized double yaw(BufferedImage imagen)throws Exception{
        cargar();
        Path temporal=Files.createTempFile("asistencia-pose-", ".jpg");
        try{
            ImageIO.write(imagen,"jpg",temporal.toFile());
            var mat=org.opencv.imgcodecs.Imgcodecs.imread(temporal.toString());
            var rostros=new org.opencv.core.Mat();
            try{
                if(mat.empty())throw new IllegalArgumentException("La imagen de prueba de vida no es válida.");
                detector.setInputSize(new Size(mat.cols(),mat.rows()));detector.detect(mat,rostros);
                if(rostros.rows()!=1)throw new IllegalArgumentException("Mantén exactamente un rostro visible durante la prueba.");
                float[] f=new float[15];rostros.get(0,0,f);
                double eyeDx=f[4]-f[6],eyeDy=f[5]-f[7];
                double eyeDistance=Math.hypot(eyeDx,eyeDy);
                if(eyeDistance<1)throw new IllegalArgumentException("No se pudieron validar los puntos faciales.");
                // Proyecta la nariz sobre el eje de los ojos, orientado a la derecha de la imagen.
                double narizX = f[8] - (f[4] + f[6]) / 2.0;
                double narizY = f[9] - (f[5] + f[7]) / 2.0;
                return Math.copySign(1, eyeDx) * (narizX * eyeDx + narizY * eyeDy)
                        / (eyeDistance * eyeDistance);
            }finally{mat.release();rostros.release();}
        }finally{Files.deleteIfExists(temporal);}
    }

    private static void cargar(){
        if(cargado)return;
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        detector=FaceDetectorYN.create(YUNET,"",new Size(320,320),0.9f,0.3f,5000);
        reconocedor=FaceRecognizerSF.create(SFACE,"");
        cargado=true;
    }
}
