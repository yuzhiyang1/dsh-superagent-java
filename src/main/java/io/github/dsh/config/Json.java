package io.github.dsh.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.dsh.management.ApiException;
import java.util.*;

/** JSON 只在管理边界使用；核心领域仍使用 Java record。 */
public final class Json {
    private Json() {}
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static JsonNode node(Object value) { return MAPPER.valueToTree(value); }
    public static ObjectNode object(Object... pairs) {
        ObjectNode result=MAPPER.createObjectNode();
        for(int i=0;i<pairs.length;i+=2) result.set((String)pairs[i],node(pairs[i+1]));
        return result;
    }
    public static ArrayNode array(Collection<?> values) { return (ArrayNode)node(values); }
    public static JsonNode parse(String text) { try { return MAPPER.readTree(text); } catch(Exception e) { throw new ApiException(400,"The request body is not JSON"); } }
    public static ObjectNode requireObject(JsonNode n,String path) { if(n==null||!n.isObject()) invalid(path,"Expected an object"); return (ObjectNode)n; }
    public static void keys(JsonNode n, String path, String... known) {
        requireObject(n,path); Set<String> allowed=Set.of(known);
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) invalid(path.isEmpty()?k:path+"."+k,"Unknown key");});
    }
    public static String string(JsonNode n,String path,int min,int max) {
        if(n==null||!n.isTextual()||n.textValue().length()<min||n.textValue().length()>max) invalid(path,"Expected text of length "+min+".."+max);
        return n.textValue();
    }
    public static int integer(JsonNode n,String path,int min,int max) {
        if(n==null||!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max) invalid(path,"Expected integer "+min+".."+max);
        return n.intValue();
    }
    public static boolean bool(JsonNode n,String path) { if(n==null||!n.isBoolean()) invalid(path,"Expected boolean"); return n.booleanValue(); }
    public static String choice(JsonNode n,String path,String... options) { String s=string(n,path,0,200); if(!Set.of(options).contains(s)) invalid(path,"Expected one of "+String.join(", ",options)); return s; }
    public static void invalid(String path,String message) { throw new ApiException(400,"Invalid value: "+path,Map.of("issues",List.of(Map.of("path",path,"message",message)))); }
    public static ObjectNode defaults(JsonNode input,ObjectNode fallback) { ObjectNode result=fallback.deepCopy(); requireObject(input,"").fields().forEachRemaining(e->result.set(e.getKey(),e.getValue().deepCopy())); return result; }
}
