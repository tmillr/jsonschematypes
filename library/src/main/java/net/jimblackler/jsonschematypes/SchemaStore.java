package net.jimblackler.jsonschematypes;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONPointer;

public class SchemaStore {
  private final Collection<URI> unbuiltPaths = new HashSet<>();
  private final Map<URI, Schema> builtPaths = new HashMap<>();
  private final Map<URI, Object> documentCache = new HashMap<>();
  private final Map<URI, URI> idToPath = new HashMap<>();
  private final Map<URI, URI> refs = new HashMap<>();
  private final Collection<UriRewriter> rewriters = new ArrayList<>();
  private final URI basePointer;

  interface UriRewriter {
    URI rewrite(URI in);
  }

  public SchemaStore() throws GenerationException {
    try {
      basePointer = new URI(null, null, null);
    } catch (URISyntaxException e) {
      throw new GenerationException(e);
    }
  }

  public void addRewriter(UriRewriter rewriter) {
    rewriters.add(rewriter);
  }

  public void loadBaseObject(Object jsonObject) throws GenerationException {
    storeDocument(basePointer, jsonObject);
    followAndQueue(basePointer);
  }

  Object fetchDocument(URI uri) throws GenerationException {
    System.out.println("Original uri " + uri);
    for (UriRewriter rewriter : rewriters) {
      uri = rewriter.rewrite(uri);
    }
    if (documentCache.containsKey(uri)) {
      return documentCache.get(uri);
    }
    String content;

    try (Scanner scanner = new Scanner(uri.toURL().openStream(), StandardCharsets.UTF_8)) {
      content = scanner.useDelimiter("\\A").next();
    } catch (IllegalArgumentException | IOException e) {
      throw new GenerationException("Error fetching " + uri, e);
    }
    Object object;
    try {
      object = new JSONArray(content);
    } catch (JSONException e) {
      try {
        object = new JSONObject(content);
      } catch (JSONException e2) {
        throw new GenerationException(e2);
      }
    }
    storeDocument(uri, object);
    return object;
  }

  private void storeDocument(URI uri, Object object) throws GenerationException {
    documentCache.put(uri, object);
    findIds(uri, null);
  }

  private void findIds(URI uri, URI activeId) throws GenerationException {
    Object object = resolve(uri);
    if (object instanceof JSONObject) {
      JSONObject jsonObject = (JSONObject) object;
      Object idObject = jsonObject.opt("$id");
      if (idObject instanceof String) {
        URI newId = URI.create((String) idObject);
        if (activeId != null) {
          newId = idRelativeUri(activeId, newId);
        }
        activeId = newId;
        idToPath.put(activeId, uri);
      }

      Object refObject = jsonObject.opt("$ref");
      if (refObject instanceof String) {
        String refObject1 = (String) refObject;
        URI refUri = URI.create(refObject1);
        System.out.println("refUri: " + refUri + " activeId: " + activeId);
        URI uri1;
        if (activeId == null || refObject1.startsWith("#")) {
          uri1 = uri.resolve(refUri);
          System.out.println("now1: " + uri1);
        } else {
          uri1 = idRelativeUri(activeId, refUri);
          System.out.println("now2: " + uri1);
        }
        refs.put(uri, uri1);
      }

      Iterator<String> it = jsonObject.keys();
      while (it.hasNext()) {
        String key = it.next();
        findIds(JsonSchemaRef.append(uri, key), activeId);
      }
    } else if (object instanceof JSONArray) {
      JSONArray jsonArray = (JSONArray) object;
      for (int idx = 0; idx != jsonArray.length(); idx++) {
        findIds(JsonSchemaRef.append(uri, String.valueOf(idx)), activeId);
      }
    }
  }

  private static URI idRelativeUri(URI id, URI uri) throws GenerationException {

    System.out.println("id: " + id);
    System.out.println("uri: " + uri);

    // See "This URI also serves as the base URI.." in
    // https://tools.ietf.org/html/draft-handrews-json-schema-02#section-8.2.2
    URI uri1 = id.resolve(uri);
    System.out.println("rewritten: " + uri1);
    return uri1;
  }

  public Object resolve(URI uri) throws GenerationException {
    System.out.println("Resolving " + uri);
    if (idToPath.containsKey(uri)) {
      uri = idToPath.get(uri);
      System.out.println("Now it's " + uri);
    }
    try {
      URI documentUri = new URI(uri.getScheme(), uri.getSchemeSpecificPart(), null);
      Object object = fetchDocument(documentUri);
      if (uri.getFragment() == null || "/".equals(uri.getFragment())) {
        return object;
      }
      JSONPointer jsonPointer = new JSONPointer("#" + uri.getFragment());
      return jsonPointer.queryFrom(object);
    } catch (URISyntaxException e) {
      throw new GenerationException(e);
    }
  }

  public URI followAndQueue(URI uri) {
    // URI must be a path and NOT an id.
    if (unbuiltPaths.contains(uri) || builtPaths.containsKey(uri)) {
      return uri;
    }

    if (refs.containsKey(uri)) {
      URI uri1 = refs.get(uri);
      // This could be an ID. Convert it back to a path.

      // but how??? Look at all ids for prefixes?
      if (idToPath.containsKey(uri1)) {
        System.out.println("id is " + uri1);
        uri1 = idToPath.get(uri1);
        System.out.println("path is " + uri1);
      }
      return followAndQueue(uri1);
    }
    unbuiltPaths.add(uri);
    return uri;
  }

  public void process() throws GenerationException {
    while (!unbuiltPaths.isEmpty()) {
      URI uri = unbuiltPaths.iterator().next();
      System.out.println("Processing " + uri);
      builtPaths.put(uri, Schemas.create(this, uri));
      unbuiltPaths.remove(uri);
    }
  }

  public void loadResources(Path resources) throws GenerationException {
    try (Stream<Path> walk = Files.walk(resources)) {
      for (Path path : walk.collect(Collectors.toList())) {
        if (Files.isDirectory(path)) {
          continue;
        }
        followAndQueue(new URI("file", path.toString(), null));
      }
    } catch (UncheckedGenerationException | IOException | URISyntaxException ex) {
      throw new GenerationException(ex);
    }
  }
}
