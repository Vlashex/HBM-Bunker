package dev.deadearth.operator;

import dev.deadearth.director.CinematicCatalog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Fixed UUID staging files and atomic publication. Source hashes cover exact UTF-8 bytes. */
public final class CatalogStaging {
    public record Document(String source,CinematicCatalog catalog,String sourceHash) {}
    public static final class SourceChangedException extends IOException {
        public SourceChangedException(){super("Loaded catalog source changed");}
    }
    private CatalogStaging() {}
    public static Document parse(String source) {
        var bytes=source.getBytes(StandardCharsets.UTF_8);
        return new Document(source,CinematicCatalog.parse(source),hash(bytes));
    }
    private static String hash(byte[] source) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void parents(Path file)throws IOException {
        for(Path parent=file.toAbsolutePath().normalize().getParent();parent!=null;parent=parent.getParent())
            if(Files.isSymbolicLink(parent)||Files.exists(parent,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Catalog parent must be a regular directory");
    }
    public static Document read(Path file)throws IOException {
        parents(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)>CinematicCatalog.MAX_BYTES)
            throw new IOException("Catalog file must be regular and bounded");
        byte[] bytes;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(CinematicCatalog.MAX_BYTES+1);}
        if(bytes.length>CinematicCatalog.MAX_BYTES)throw new IOException("Catalog file exceeds limit");
        String source=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return new Document(source,CinematicCatalog.parse(source),hash(bytes));
    }
    public static Document stage(Path catalogFile,UUID stage)throws IOException {
        return read(catalogFile.resolveSibling("staging").resolve(stage.toString()+".json"));
    }
    private static void current(Path file,Document loaded,boolean existed)throws IOException {
        boolean present=Files.exists(file,LinkOption.NOFOLLOW_LINKS);
        if(present!=existed)throw new SourceChangedException();
        if(present) {
            Document disk;
            try{disk=read(file);}catch(IllegalArgumentException invalid){throw new SourceChangedException();}
            if(!disk.sourceHash().equals(loaded.sourceHash()))throw new SourceChangedException();
        }
    }
    private static void durableWrite(Path file,String source,boolean create)throws IOException {
        var options=new HashSet<OpenOption>(Set.of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS));
        options.add(create?StandardOpenOption.CREATE_NEW:StandardOpenOption.TRUNCATE_EXISTING);
        try(var output=FileChannel.open(file,options)) {
            var bytes=ByteBuffer.wrap(source.getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())output.write(bytes);output.force(true);
        }
    }
    /** No non-atomic fallback: an unsupported move leaves the loaded and on-disk catalogs intact. */
    public static void publish(Path file,Document loaded,boolean existed,Document next)throws IOException {
        parents(file);current(file,loaded,existed);
        Path parent=file.toAbsolutePath().normalize().getParent();Files.createDirectories(parent);parents(file);
        Path backups=parent.resolve("backups");parents(backups.resolve("backup.json"));Files.createDirectories(backups);
        durableWrite(backups.resolve(UUID.randomUUID()+".json"),loaded.source(),true);
        Path temporary=Files.createTempFile(parent,".catalog-",".tmp");
        try {
            durableWrite(temporary,next.source(),false);
            parents(file);current(file,loaded,existed);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
}
