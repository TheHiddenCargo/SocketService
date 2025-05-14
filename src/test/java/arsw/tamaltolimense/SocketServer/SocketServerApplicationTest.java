package arsw.tamaltolimense.SocketServer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SocketServerApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // Verifica que el contexto de Spring se carga correctamente
        assertNotNull(applicationContext);
    }

    @Test
    void mainMethodStartsApplication() {
        // Prueba que el método main se ejecuta sin errores
        SocketServerApplication.main(new String[]{});
    }

}