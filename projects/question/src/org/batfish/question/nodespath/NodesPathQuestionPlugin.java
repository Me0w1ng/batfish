package org.batfish.question.nodespath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.batfish.common.Answerer;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.question.QuestionPlugin;
import org.batfish.question.NodesQuestionPlugin.NodesAnswerer;
import org.batfish.question.NodesQuestionPlugin.NodesQuestion;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.Configuration.ConfigurationBuilder;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;

public class NodesPathQuestionPlugin extends QuestionPlugin {

   public static class NodesPathAnswerElement implements AnswerElement {

      private SortedMap<Integer, NodesPathResult> _results;

      public NodesPathAnswerElement() {
         _results = new TreeMap<>();
      }

      public SortedMap<Integer, NodesPathResult> getResults() {
         return _results;
      }

      @Override
      public String prettyPrint() throws JsonProcessingException {
         StringBuilder sb = new StringBuilder("Results for nodespath\n");

         for (Integer index : _results.keySet()) {
            NodesPathResult result = _results.get(index);            
            sb.append(String.format("  [%d]: %d results for %s\n", index, result.getNumResults(), result.getPath().toString()));            
            for (String path : result.getResult().keySet()) {
               JsonNode suffix = result.getResult().get(path);
               if (suffix != null)
                  sb.append(String.format("    %s : %s\n", path, result.getResult().get(path).toString()));
               else 
                  sb.append(String.format("    %s\n", path));
            }            
         }
         
         return sb.toString();
      }

      public void setResults(SortedMap<Integer, NodesPathResult> results) {
         _results = results;
      }

   }

   public static class NodesPathAnswerer extends Answerer {

      public NodesPathAnswerer(Question question, IBatfish batfish) {
         super(question, batfish);
      }

      @Override
      public AnswerElement answer() {

         ConfigurationBuilder b = new ConfigurationBuilder();
         b.jsonProvider(new JacksonJsonNodeJsonProvider());
         final Configuration c = b.build();

         NodesPathQuestion question = (NodesPathQuestion) _question;
         List<NodesPath> paths = question.getPaths();

         _batfish.checkConfigurations();

         NodesQuestion nodesQuestion = new NodesQuestion();
         nodesQuestion.setSummary(false);
         NodesAnswerer nodesAnswerer = new NodesAnswerer(nodesQuestion,
               _batfish);
         AnswerElement nodesAnswer = nodesAnswerer.answer();
         BatfishObjectMapper mapper = new BatfishObjectMapper();
         String nodesAnswerStr = null;
         try {
            nodesAnswerStr = mapper.writeValueAsString(nodesAnswer);
         }
         catch (IOException e) {
            throw new BatfishException(
                  "Could not get JSON string from nodes answer", e);
         }
         Object jsonObject = JsonPath.parse(nodesAnswerStr, c).json();
         Map<Integer, NodesPathResult> results = new ConcurrentHashMap<>();
         List<Integer> indices = new ArrayList<>();
         for (int i = 0; i < paths.size(); i++) {
            indices.add(i);
         }
         indices.parallelStream().forEach(i -> {
            NodesPath nodesPath = paths.get(i);
            String path = nodesPath.getPath();

            ConfigurationBuilder prefixCb = new ConfigurationBuilder();
            prefixCb.mappingProvider(c.mappingProvider());
            prefixCb.jsonProvider(c.jsonProvider());
            prefixCb.evaluationListener(c.getEvaluationListeners());
            prefixCb.options(c.getOptions());
            prefixCb.options(Option.ALWAYS_RETURN_LIST);
            prefixCb.options(Option.AS_PATH_LIST);
            Configuration prefixC = prefixCb.build();

            ConfigurationBuilder suffixCb = new ConfigurationBuilder();
            suffixCb.mappingProvider(c.mappingProvider());
            suffixCb.jsonProvider(c.jsonProvider());
            suffixCb.evaluationListener(c.getEvaluationListeners());
            suffixCb.options(c.getOptions());
            suffixCb.options(Option.ALWAYS_RETURN_LIST);
            Configuration suffixC = suffixCb.build();

            ArrayNode prefixes = null;
            ArrayNode suffixes = null;
            JsonPath jsonPath = JsonPath.compile(path);

            try {
               prefixes = jsonPath.read(jsonObject, prefixC);
               suffixes = jsonPath.read(jsonObject, suffixC);
            }
            catch (PathNotFoundException e) {
               suffixes = JsonNodeFactory.instance.arrayNode();
               prefixes = JsonNodeFactory.instance.arrayNode();
            }
            catch (Exception e) {
               throw new BatfishException("Error reading JSON path: " + path,
                     e);
            }
            int numResults = prefixes.size();
            NodesPathResult nodePathResult = new NodesPathResult();
            nodePathResult.setPath(nodesPath);
            nodePathResult.setNumResults(numResults);
            boolean includeSuffix = nodesPath.getSuffix();
            if (!nodesPath.getSummary()) {
               SortedMap<String, JsonNode> result = new TreeMap<>();
               Iterator<JsonNode> p = prefixes.iterator();
               Iterator<JsonNode> s = suffixes.iterator();
               while (p.hasNext()) {
                  JsonNode prefix = p.next();
                  JsonNode suffix = includeSuffix ? s.next() : null;
                  String prefixStr = prefix.textValue();
                  if (prefixStr == null) {
                     throw new BatfishException("Did not expect null value");
                  }
                  result.put(prefixStr, suffix);
               }
               nodePathResult.setResult(result);
            }
            results.put(i, nodePathResult);
         });
         NodesPathAnswerElement answerElement = new NodesPathAnswerElement();
         answerElement.getResults().putAll(results);

         return answerElement;
      }
   }

   // <question_page_comment>
   /**
    * Runs JsonPath <https://github.com/jayway/JsonPath> queries on the JSON
    * data model that is the output of the 'Nodes' question.
    * <p>
    * This query can be used to perform server-side queries for the presence or
    * absence of specified patterns in the data model induced by the
    * configurations supplied in the test-rig.
    *
    * @type NodesPath onefile
    *
    * @param paths
    *           A JSON list of path queries, each of which is a JSON object
    *           containing the remaining documented fields (path, suffix,
    *           summary). For each specified path query, the question returns a
    *           list of paths in the data model matching the criteria of the
    *           query.
    *
    * @hparam path (Property of each element of 'paths') The JsonPath query to
    *         execute.
    *
    * @hparam suffix (Property of each element of 'paths') Defaults to false. If
    *         true, then each path in the returned list will map to the
    *         remaining content of the datamodel at the end of that path. This
    *         can be useful for debugging, but can also be very verbose. If
    *         false, then each path will map to a null value.
    *
    * @hparam summary (Property of each element of 'paths') Defaults to false.
    *         If true, then instead of outputting each matching path, only the
    *         count of matching paths will be output.
    *
    * @example bf_answer("NodesPath",paths=[{"path":"$.nodes[*].interfaces[*][?(@.mtu!=1500)].mtu"}])
    *          Return all interfaces with MTUs not equal to 1500
    *
    */
   public static class NodesPathQuestion extends Question {

      private static final String PATHS_VAR = "paths";

      private List<NodesPath> _paths;

      public NodesPathQuestion() {
         _paths = Collections.emptyList();
      }

      @Override
      public boolean getDataPlane() {
         return false;
      }

      @Override
      public String getName() {
         return "nodespath";
      }

      public List<NodesPath> getPaths() {
         return _paths;
      }

      @Override
      public boolean getTraffic() {
         return false;
      }

      @Override
      public String prettyPrint() {
         String retString = String.format("%s %s%s=\"%s\"", getName(),
               prettyPrintBase(), PATHS_VAR, _paths);
         return retString;
      }

      @Override
      public void setJsonParameters(JSONObject parameters) {
         super.setJsonParameters(parameters);
         Iterator<?> paramKeys = parameters.keys();
         while (paramKeys.hasNext()) {
            String paramKey = (String) paramKeys.next();
            if (isBaseParamKey(paramKey)) {
               continue;
            }
            try {
               switch (paramKey) {
               case PATHS_VAR:
                  setPaths(new ObjectMapper().<List<NodesPath>> readValue(
                        parameters.getString(paramKey),
                        new TypeReference<List<NodesPath>>() {
                        }));
                  break;
               default:
                  throw new BatfishException("Unknown key in "
                        + getClass().getSimpleName() + ": " + paramKey);
               }
            }
            catch (JSONException | IOException e) {
               throw new BatfishException("JSONException in parameters", e);
            }
         }
      }

      public void setPaths(List<NodesPath> paths) {
         _paths = paths;
      }

   }

   @Override
   protected Answerer createAnswerer(Question question, IBatfish batfish) {
      return new NodesPathAnswerer(question, batfish);
   }

   @Override
   protected Question createQuestion() {
      return new NodesPathQuestion();
   }

}
