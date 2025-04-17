package util.tools;

import org.tinylog.Logger;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileTools {

    /* ******************************* R E A D / W R I T E ****************************************************** */
    public static void setAllPermissions(Path file){

        String os = System.getProperty("os.name").toLowerCase();
        if( !os.startsWith("linux") || Files.notExists(file)){
            Logger.info("Couldn't set permissions, because wrong os or non-existing file");
            return;
        }

        Set<PosixFilePermission> perms = new HashSet<>();
        // Owner
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);

        // Group
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_WRITE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);

        // Others
        perms.add(PosixFilePermission.OTHERS_READ);
        perms.add(PosixFilePermission.OTHERS_WRITE);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);

        try {
            Files.setPosixFilePermissions(file, perms);
            Logger.info( "File permission set successfully: "+file );
        } catch (IOException e) {
            Logger.error(e);
        }
    }
    /**
     * Method that opens a file (or creates it and needed directories) and appends the data to it
     * 
     * @param path The path to the file
     * @param text The data to append
     * @return True if all was successful
     */
    public static synchronized boolean appendToTxtFile( Path path, String text ) {
        if( path == null)
            return false;

    	try {
            if( path.getParent() != null && Files.notExists(path.getParent() )){
                Files.createDirectories(path.getParent());
            }
    		Files.write( path, text.getBytes() , StandardOpenOption.CREATE, StandardOpenOption.APPEND );
		} catch (NullPointerException | IOException e) {			
            Logger.error(e);
            return false;
        }
        return true;
    }
    /**
     * Reads a file and puts the contents in an ArrayList, 1 line is one item
     *
     * @param content The ArrayList to hold the data
     * @param path    The path of the file
     */
    public static void readTxtFile(ArrayList<String> content, Path path) {
        content.addAll(readLines(path, 1, -1, true));
    }
    /**
     * Read amount of lines from a file starting at start
     * 
     * @param path The path to the file
     * @param start Starting point (first line = 1)
     * @param amount The amount of lines to read, if less lines are available it will read till end of file
     * @return List of read lines or an empty list of none were read
     */
    public static ArrayList<String> readLines(Path path, long start, long amount, boolean utfFirst) {

        var read = new ArrayList<String>();
        if( start==0) {
            start=1;
            amount++;
        }
        if( Files.notExists(path)){
            Logger.error("Tried to read lines from "+path+" but no such file");
            return read;
        }
        if( start<0 || amount==0 )
            return read;

        if (utfFirst) {
            if (!readLimitedLines(path, start, amount, StandardCharsets.UTF_8, read)) {
                Logger.info("Retrying with ISO_8859_1");
                readLimitedLines(path, start, amount, StandardCharsets.ISO_8859_1, read);
            }
        } else {
            if (!readLimitedLines(path, start, amount, StandardCharsets.ISO_8859_1, read)) {
                Logger.info("Retrying with UTF8");
                readLimitedLines(path, start, amount, StandardCharsets.UTF_8, read);
            }
        }
        return read;
    }

    private static boolean readLimitedLines(Path path, long start, long numLines, Charset cs, ArrayList<String> read) {
        try( var lines = Files.lines(path, cs) ) {
            var stream = lines.skip(start - 1);
            if (numLines != -1) {
                stream.limit(numLines)    // Limit the next lines to 'numLines'
                        .forEach(read::add); // Add each line to the 'read' list
            } else {
                stream.forEach(read::add);
            }
                return true;
        } catch (UncheckedIOException d) {
            Logger.error("Malformed Failed reading with (charset) " + cs + " while reading " + path);
            read.clear();
        } catch (IOException ex) {
            Logger.error(ex);
        }
        return false;
    }
    /**
     * Read the last 'amount' of lines from a file
     * 
     * @param path The full path to the file
     * @param amount The max amount of files to read
     * @return A list of the read files or an empty list if none
     */
    public static List<String> readLastLines( Path path, int amount ){
        var read = new ArrayList<String>();        
        if( amount<0 )
            return read;
        try( var all = Files.lines( path )) {
            long cnt = all.count()-amount;
            cnt = cnt<0?0:cnt;
            try( var lines = Files.lines( path )) {
                lines.skip( cnt )                     
                .forEach( read::add );
                read.trimToSize();
                return read;
            }
        } catch (IOException ex) {
            Logger.error(ex);
            return read;
        }
    }
    /* ******************************* C O M P R E S S I O N******************************************************/
    /**
     * Zips a file and puth the resulting file in the same folder
     * @param filePath The path to the file to zip
     * @return The absolute path of the resulting file or null if something failed
     */
    public static Optional<Path> zipFile(Path filePath, boolean deleteOriginal) {

        if(Files.notExists(filePath) )
            return Optional.empty();
        try (ZipOutputStream zos = new ZipOutputStream( new FileOutputStream(filePath+".zip") ) ) {
            zos.putNextEntry(new ZipEntry(filePath.getFileName().toString()));                                               
            zos.write( Files.readAllBytes(filePath), 0, (int)Files.size(filePath));
            zos.closeEntry();
            Logger.debug("Created ZIP: "+filePath.getFileName()+".zip");
            if (deleteOriginal)
                Files.deleteIfExists(filePath);
            if(filePath.getParent()!=null)
                return Optional.of(Path.of(filePath.getParent().toString(), filePath.getFileName() + ".zip").toAbsolutePath());
            return Optional.of(Path.of(filePath.getFileName() + ".zip").toAbsolutePath());
        } catch (FileNotFoundException ex) {
            Logger.error("The file %s does not exist", filePath);
            return Optional.empty();
        } catch (NullPointerException |  IOException e) {
            Logger.error(e);
            return Optional.empty();
        }
    }
    /**
     * Returns a zip file system
     * 
     * @param zipFilename to construct the file system from
     * @param create true if the zip file should be created
     * @return a zip file system
     * @throws IOException
     */
    private static FileSystem createZipFileSystem(String zipFilename, boolean create) throws IOException {
        // convert the filename to a URI
        final Path path = Path.of(zipFilename);
        final URI uri = URI.create("jar:file:" + path.toUri().getPath().replace(" ","%20"));

        final Map<String, String> env = new HashMap<>();
        if (create) {
            env.put("create", "true");
        }
        return FileSystems.newFileSystem(uri, env);
    }
    /**
     * Unzips the specified zip file to the specified destination directory.
     * Replaces any files in the destination, if they already exist.
     * 
     * @param zipFilename the name of the zip file to extract
     * @param destDirname the directory to unzip to
     * @throws IOException When a file doesn't exist
     */
    public static void unzipFile(String zipFilename, String destDirname)  throws IOException{
    
        final Path destDir = Path.of(destDirname);
        //if the destination doesn't exist, create it
        if(Files.notExists(destDir)){
           Files.createDirectories(destDir);
        }
        
        try (FileSystem zipFileSystem = createZipFileSystem(zipFilename, false)){
            final Path root = zipFileSystem.getPath("/");
        
            //walk the zip file tree and copy files to the destination
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final Path destFile = Path.of(destDir.toString(),
                            file.toString());
                    Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    final Path dirToCreate = Path.of(destDir.toString(), dir.toString());
                    if (Files.notExists(dirToCreate)) {
                        Files.createDirectory(dirToCreate);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
    /* ************************************ F T P *************************************************************** */
    /**
     * Retrieve a file from an FTP server
     * 
     * @param user User that has access to the ftp
     * @param pass Password of this user
     * @param server The ip or hostname of this server
     * @param port The port to connect to
     * @param source The file to retrieve
     * @param destination The destination to store the file at
     * @return TRue if it was succesful
     */
    public static boolean retrieveFileFromFTP( String user, String pass, String server, int port,String source,Path destination ){
        String url = "ftp://"+user+":"+pass+"@"+server+":"+port+"/"+source;
        try {            
            URLConnection urlConnection = new URL( url ).openConnection();
            InputStream inputStream = urlConnection.getInputStream();
            Files.copy(inputStream, destination); // StandardCopyOption.REPLACE_EXISTING
            inputStream.close();         
            Logger.error("File copied successfully: "+source);
            return true;
        }catch( FileNotFoundException e ){ 
            Logger.error("No such file: "+source);
        }catch(java.net.ConnectException e ){
            Logger.error( "Failed to connect to ftp server:"+server );
        }catch (IOException e) {
            Logger.error( e );
        }
        return false;
    }
    /**
     * Sends a file to a FTP server
     * 
     * @param user User that has access to the ftp
     * @param pass Password of this user
     * @param server The ip or hostname of this server
     * @param port The port to connect to
     * @param destination The destination to store the file at
     * @param source The file to retrieve
     * @return TRue if it was succesful
     */
    public static boolean sendFileToFTP( String user, String pass, String server, int port,String destination, Path source ){  
        try {
            String url = "ftp://"+user+":"+pass+"@"+server+":"+port+"/"+destination;
            URLConnection urlConnection = new URL( url ).openConnection();
            OutputStream outputStream = urlConnection.getOutputStream();
            Files.copy(source, outputStream);
            outputStream.close();
            return true;
        }catch( FileNotFoundException e ){ 
            Logger.error("tNo such file: "+source);
        }catch(java.net.ConnectException e ){
            Logger.error( "Failed to connect to ftp server:"+server );
        }catch (IOException e) {
            Logger.error(e.getMessage());
        }
        return false;     
    }
    /**
     * Read a resource (aka file inside the jar) ascii file
     * @param origin The origin class
     * @param filename The name of the file, / will be prepended if not provided
     * @return And empty optional if something went wrong or containing the string if it worked
     */
    public static Optional<String> getResourceStringContent(Class origin, String filename){

        if( !filename.startsWith("/"))
            filename = "/"+filename;

        InputStream is = origin.getResourceAsStream(filename);

        if (is == null) {
            Logger.error( filename+ " not found");
        } else {
            try (InputStreamReader streamReader =
                         new InputStreamReader(is, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(streamReader)) {
                var j = new StringJoiner(System.lineSeparator());
                reader.lines().forEach(j::add);
                return Optional.of(j.toString());
            } catch (IOException e) {
                Logger.error(e);
            }
        }
        return Optional.empty();
    }
    public static List<Path> findByFileName(Path path, int deep, String regex){
        try (Stream<Path> pathStream = Files.find(path,
                deep==-1?Integer.MAX_VALUE:deep,
                (p, basicFileAttributes) ->
                        p.getFileName().toString().matches(Pattern.quote(regex)))
        ) {
            return pathStream.sorted().collect(Collectors.toList());
        }catch( Exception e){
            Logger.error(e);
        }
        return new ArrayList<>();
    }
}