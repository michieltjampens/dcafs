package das;

import org.apache.commons.lang3.SystemUtils;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import java.nio.file.Path;

public class Paths {

    // Static fields to store the paths
    public static final Path FILE_STORAGE_PATH;
    public static final Path SETTINGS_XML_PATH;


    static {
        String classPath = Paths.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        classPath = classPath.replace("%20", " ");
        System.out.println("Checking for workpath at: " + classPath);

        if (classPath.startsWith("/") && SystemUtils.IS_OS_WINDOWS) {
            classPath = classPath.substring(1); // Windows path prepended with an extra "/"
        }

        Path p = Path.of(classPath); // Convert to Path

        if (classPath.endsWith("classes/")) { // If running from IDE
            p = p.getParent(); // Get parent to get out of the "classes" directory
        }

        var workPath = p.getParent().toString();
        if (workPath.matches(".*lib$")) { // If running as a library
            workPath = Path.of(workPath).getParent().toString();
        } else if (workPath.contains("repository")) { // If in a repository
            workPath = Path.of("").toAbsolutePath().toString();
        }

        FILE_STORAGE_PATH = Path.of(workPath);
        SETTINGS_XML_PATH = Path.of(workPath, "settings.xml");

        System.out.println("File Storage Path: " + FILE_STORAGE_PATH);
        System.out.println("Settings XML Path: " + SETTINGS_XML_PATH);
    }
    public static Path storage(){
        return FILE_STORAGE_PATH;
    }
    public static Path settings(){
        return SETTINGS_XML_PATH;
    }
    public static XMLdigger digInSettings( String sub ){
        return XMLdigger.goIn(Paths.settings(),"dcafs",sub);
    }
    public static XMLfab fabInSettings( String sub ){
        return XMLfab.withRoot(Paths.settings(),"dcafs",sub);
    }

}
