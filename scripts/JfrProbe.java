import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 诊断用：逐事件读 jfr 文件，打印第一个解析成功的类型与失败栈。 */
public final class JfrProbe {
    public static void main(String[] args) throws Exception {
        Set<String> seen = new HashSet<>();
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            int n = 0;
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                n++;
                if (seen.add(event.getEventType().getName())) {
                    System.out.println("ok: " + event.getEventType().getName());
                }
            }
            System.out.println("events read: " + n);
        } catch (Throwable t) {
            System.out.println("failed after " + seen.size() + " types");
            t.printStackTrace(System.out);
        }
    }
}
