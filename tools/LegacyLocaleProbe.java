package dev.deadearth.legacyverify;

import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import net.minecraftforge.fml.client.FMLFileResourcePack;
import net.minecraftforge.fml.common.*;

/** Genuine 1.12.2 resource-pack/Locale loading, without Minecraft.main or a display. */
public final class LegacyLocaleProbe {
 public static void main(String[] args) throws Exception {
  System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,"UTF-8"));
  if(args.length!=2)throw new IllegalArgumentException("Usage: LegacyLocaleProbe JAR LANGUAGE");
  File jar=new File(args[0]).getCanonicalFile();String language=args[1];
  ModMetadata metadata=new ModMetadata();metadata.modId="dead_earth";metadata.name="DEAD EARTH locale verification";metadata.version="probe";
  ModContainer container=new InjectedModContainer(new DummyModContainer(metadata),jar);
  FMLFileResourcePack nativePack=new FMLFileResourcePack(container);
  try {
   // Obfuscated vanilla identifiers are pinned by client-1.12.2.jar. Forge's
   // shipped FMLFileResourcePack directly extends this genuine FileResourcePack.
   // Vanilla classes are signed and live in the default package. Invoke them
   // reflectively from our own package to preserve their original signer boundary.
   Class<?> serializerType=Class.forName("cfg"),packMetadataType=Class.forName("cfr"),resourcePackType=Class.forName("cer");
   Object serializer=serializerType.getConstructor().newInstance();
   serializerType.getMethod("a",Class.forName("cff"),Class.class).invoke(serializer,Class.forName("cfs").getConstructor().newInstance(),packMetadataType);
   Object packMetadata=nativePack.getClass().getMethod("a",serializerType,String.class).invoke(nativePack,serializer,"pack");
   int format=((Number)packMetadataType.getMethod("b").invoke(packMetadata)).intValue();
   Object selected=format==2?Class.forName("ces").getConstructor(resourcePackType).newInstance(nativePack):nativePack;
   // This is exactly the selection made by FMLClientHandler.addModAsResource.
   Class<?> resourcesType=Class.forName("cev"),localeType=Class.forName("cfb");
   Object resources=resourcesType.getConstructor(serializerType).newInstance(serializer);
   resourcesType.getMethod("a",resourcePackType).invoke(resources,selected);
   Object locale=localeType.getConstructor().newInstance();
   localeType.getMethod("a",Class.forName("cep"),List.class).invoke(locale,resources,Arrays.asList("en_us",language));
   Map<String,String> expected=new LinkedHashMap<String,String>();
   try(ZipFile archive=new ZipFile(jar)) {
    Enumeration<? extends ZipEntry> entries=archive.entries();
    while(entries.hasMoreElements()) {
     ZipEntry entry=entries.nextElement();String name=entry.getName();
     if(!name.startsWith("assets/dead_earth")||!name.endsWith("/lang/"+language+".lang"))continue;
     try(BufferedReader input=new BufferedReader(new InputStreamReader(archive.getInputStream(entry),StandardCharsets.UTF_8))) {
      for(String line;(line=input.readLine())!=null;) {
       if(line.isEmpty()||line.charAt(0)=='#')continue;int equal=line.indexOf('=');
       if(equal>0&&line.substring(0,equal).endsWith(".name"))expected.put(line.substring(0,equal),line.substring(equal+1));
      }
     }
    }
   }
   if(expected.isEmpty())throw new IllegalStateException("No packaged own *.name translations for "+language);
   int matched=0;List<Map<String,Object>> failures=new ArrayList<Map<String,Object>>(),samples=new ArrayList<Map<String,Object>>();
   for(Map.Entry<String,String> entry:expected.entrySet()) {
    String actual=(String)localeType.getMethod("a",String.class,Object[].class).invoke(locale,entry.getKey(),new Object[0]);boolean present=(Boolean)localeType.getMethod("a",String.class).invoke(locale,entry.getKey());boolean equal=entry.getValue().equals(actual);
    Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("key",entry.getKey());result.put("expected",entry.getValue());result.put("actual",actual);result.put("presentInNativeLocale",present);
    if(equal&&present)matched++;else if(failures.size()<12)failures.add(result);
    if(entry.getKey().contains("compact_sieve_v3")||entry.getKey().contains("compact_mesh_diamond_v5")||entry.getKey().contains("compressed_gravel_2")||entry.getKey().contains("zone_relay"))samples.add(result);
   }
   Map<String,Object> report=new LinkedHashMap<String,Object>();report.put("schema",1);report.put("jar",jar.getPath());report.put("language",language);report.put("packFormat",format);report.put("selectedAdapter",selected.getClass().getName());report.put("resourceDomains",resourcePackType.getMethod("c").invoke(selected));report.put("expectedNames",expected.size());report.put("resolvedNames",matched);report.put("unresolvedNames",expected.size()-matched);report.put("samples",samples);report.put("failures",failures);report.put("passed",matched==expected.size());
   System.out.println(new GsonBuilder().disableHtmlEscaping().create().toJson(report));
  } finally {nativePack.close();}
 }
}
