package net.vulkanmod.vulkan.shader.converter;

import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;

public class GLSLParser {
   private static final Set<String> INTERPOLATION_QUALIFIERS = Set.of("flat", "smooth", "noperspective", "centroid", "invariant", "precise");
   private static final Set<String> PRECISION_QUALIFIERS = Set.of("lowp", "mediump", "highp");
   private static final Set<String> STORAGE_QUALIFIERS = Set.of("uniform", "in", "out", "attribute", "varying");
   private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)");
   private static final Map<String, String> LEGACY_FUNCTIONS = Map.of(
      "texture2D", "texture",
      "texture2DLod", "textureLod",
      "texture2DProj", "textureProj",
      "textureCube", "texture",
      "textureCubeLod", "textureLod",
      "shadow2D", "texture"
   );
   private List<Token> tokens;
   private int pos;
   private GLSLParser.Stage stage;
   private final Deque<GLSLParser.Condition> conditions = new ArrayDeque<>();
   private final Set<String> defines = new HashSet<>();
   private final Set<String> vertexIdentifiers = new HashSet<>();
   private final Set<String> fragmentIdentifiers = new HashSet<>();
   LinkedList<GLSLParser.Node> vsStream = new LinkedList<>();
   LinkedList<GLSLParser.Node> fsStream = new LinkedList<>();
   int currentUniformLocation = 0;
   List<UniformBlock> uniformBlocks = new ArrayList<>();
   Map<String, UniformBlock> uniformBlockMap = new HashMap<>();
   List<Sampler> samplers = new ArrayList<>();
   Map<String, Sampler> samplerMap = new HashMap<>();
   Set<UniformBlock.Field> legacyUniforms = new LinkedHashSet<>();
   VertexFormat vertexFormat;
   ArrayList<Attribute> vertInAttributes = new ArrayList<>();
   ArrayList<Attribute> vertOutAttributes = new ArrayList<>();
   ArrayList<Attribute> fragInAttributes = new ArrayList<>();
   ArrayList<Attribute> fragOutAttributes = new ArrayList<>();
   ArrayList<InterfaceBlock> vertOutBlocks = new ArrayList<>();
   private int nextVaryingLocation;
   private int nextFragOutLocation;

   public void setVertexFormat(VertexFormat vertexFormat) {
      this.vertexFormat = vertexFormat;
   }

   public void parse(Lexer lexer, GLSLParser.Stage stage) {
      this.stage = stage;
      this.tokens = lexer.tokenize();
      this.pos = 0;
      this.conditions.clear();
      this.defines.clear();
      this.parseVersion();

      while (this.current().type != Token.TokenType.EOF) {
         Token token = this.current();
         if (token.type == Token.TokenType.PREPROCESSOR) {
            this.parsePreprocessor();
            continue;
         }

         if (token.type == Token.TokenType.IDENTIFIER) {
            this.identifiers().add(token.value);
            if (this.isActive() && this.atStatementStart() && this.isDeclarationStart(token.value) && this.parseDeclaration()) {
               continue;
            }
         }

         this.appendToken(token);
         this.pos++;
      }

      if (this.stage == GLSLParser.Stage.VERTEX) {
         this.assignVertexInputLocations();
      }
   }

   private void assignVertexInputLocations() {
      int next = 0;
      Set<Integer> used = new HashSet<>();
      List<String> attributeNames = this.vertexFormat != null ? this.vertexFormat.getElementAttributeNames() : List.of();
      if (this.vertexFormat != null) {
         next = attributeNames.size();
      }

      for (Attribute attribute : this.vertInAttributes) {
         int index = attributeNames.indexOf(attribute.id);
         if (index >= 0) {
            attribute.setLocation(index);
            used.add(index);
         }
      }

      for (Attribute attribute : this.vertInAttributes) {
         if (attribute.location == -1 && this.vertexFormat == null && attribute.explicitLocation >= 0 && !used.contains(attribute.explicitLocation)) {
            attribute.setLocation(attribute.explicitLocation);
            used.add(attribute.explicitLocation);
         }
      }

      for (Attribute attribute : this.vertInAttributes) {
         if (attribute.location == -1) {
            while (used.contains(next)) {
               next++;
            }

            attribute.setLocation(next);
            used.add(next);
            next += attribute.slotCount();
         }
      }
   }

   private Token current() {
      return this.tokens.get(this.pos);
   }

   private Token tokenAt(int index) {
      return this.tokens.get(Math.min(index, this.tokens.size() - 1));
   }

   private int skipSpace(int index) {
      while (index < this.tokens.size() - 1) {
         Token.TokenType type = this.tokens.get(index).type;
         if (type != Token.TokenType.SPACING && type != Token.TokenType.COMMENT) {
            break;
         }

         index++;
      }

      return index;
   }

   private int nextSignificant(int index) {
      return this.skipSpace(index + 1);
   }

   private Set<String> identifiers() {
      return this.stage == GLSLParser.Stage.VERTEX ? this.vertexIdentifiers : this.fragmentIdentifiers;
   }

   private void parseVersion() {
      int index = this.skipSpace(0);
      Token hash = this.tokenAt(index);
      Token directive = this.tokenAt(index + 1);
      if (hash.type == Token.TokenType.PREPROCESSOR && directive.type == Token.TokenType.IDENTIFIER && directive.value.equals("version")) {
         index += 2;
         while (this.tokenAt(index).type != Token.TokenType.EOF && !this.lineEnds(this.tokenAt(index))) {
            index++;
         }

         this.pos = Math.min(index + 1, this.tokens.size() - 1);
      } else {
         this.pos = 0;
      }

      this.appendToken(new Token(Token.TokenType.PREPROCESSOR, "#version 450\n"));
   }

   private boolean lineEnds(Token token) {
      return token.value != null && token.value.contains("\n");
   }

   private void parsePreprocessor() {
      int start = this.pos;
      int index = start + 1;
      while (this.tokenAt(index).type == Token.TokenType.SPACING && !this.lineEnds(this.tokenAt(index))) {
         index++;
      }

      Token directive = this.tokenAt(index);
      String name = directive.type == Token.TokenType.IDENTIFIER ? directive.value : "";
      int argument = this.nextSignificant(index);
      String argumentValue = this.tokenAt(argument).type == Token.TokenType.IDENTIFIER ? this.tokenAt(argument).value : null;
      boolean keep = true;
      switch (name) {
         case "define":
            if (this.isActive() && argumentValue != null) {
               this.defines.add(argumentValue);
            }
            break;
         case "undef":
            if (this.isActive() && argumentValue != null) {
               this.defines.remove(argumentValue);
            }
            break;
         case "ifdef":
            this.pushCondition(argumentValue != null && this.defines.contains(argumentValue), true);
            break;
         case "ifndef":
            this.pushCondition(argumentValue == null || !this.defines.contains(argumentValue), true);
            break;
         case "if":
            this.pushCondition(true, false);
            break;
         case "elif":
            if (!this.conditions.isEmpty()) {
               GLSLParser.Condition condition = this.conditions.peek();
               condition.active = condition.parentActive && (!condition.known || !condition.taken);
               condition.known = false;
            }
            break;
         case "else":
            if (!this.conditions.isEmpty()) {
               GLSLParser.Condition condition = this.conditions.peek();
               condition.active = condition.parentActive && (!condition.known || !condition.taken);
               condition.taken |= condition.active;
            }
            break;
         case "endif":
            if (!this.conditions.isEmpty()) {
               this.conditions.pop();
            }
            break;
         case "line":
            keep = false;
      }

      int end = start;
      while (this.tokenAt(end).type != Token.TokenType.EOF && !this.lineEnds(this.tokenAt(end))) {
         end++;
      }

      for (int i = start; i <= end && this.tokenAt(i).type != Token.TokenType.EOF; i++) {
         if (keep) {
            this.appendToken(new Token(Token.TokenType.PREPROCESSOR, this.tokens.get(i).value));
         } else if (i == end && this.lineEnds(this.tokenAt(i))) {
            this.appendToken(new Token(Token.TokenType.SPACING, "\n"));
         }
      }

      this.pos = Math.min(end + 1, this.tokens.size() - 1);
   }

   private void pushCondition(boolean result, boolean known) {
      boolean parentActive = this.isActive();
      GLSLParser.Condition condition = new GLSLParser.Condition();
      condition.parentActive = parentActive;
      condition.known = known;
      condition.active = parentActive && result;
      condition.taken = condition.active;
      this.conditions.push(condition);
   }

   private boolean isActive() {
      return this.conditions.isEmpty() || this.conditions.peek().active;
   }

   private boolean atStatementStart() {
      LinkedList<GLSLParser.Node> nodes = this.getNodeStream();
      for (int i = nodes.size() - 1; i >= 0; i--) {
         GLSLParser.Node node = nodes.get(i);
         if (!(node instanceof GLSLParser.TokenNode tokenNode)) {
            return true;
         }

         String type = tokenNode.type;
         if (type.equals(Token.TokenType.SPACING.name()) || type.equals(Token.TokenType.COMMENT.name())) {
            continue;
         }

         return type.equals(Token.TokenType.PREPROCESSOR.name())
            || type.equals(Token.TokenType.SEMICOLON.name())
            || type.equals(Token.TokenType.LEFT_BRACE.name())
            || type.equals(Token.TokenType.RIGHT_BRACE.name());
      }

      return true;
   }

   private boolean isDeclarationStart(String value) {
      return value.equals("layout") || INTERPOLATION_QUALIFIERS.contains(value) || STORAGE_QUALIFIERS.contains(value);
   }

   private boolean parseDeclaration() {
      int index = this.pos;
      List<String> qualifiers = new ArrayList<>();
      String layout = null;

      while (true) {
         Token token = this.tokenAt(index);
         if (token.type != Token.TokenType.IDENTIFIER) {
            return false;
         }

         if (token.value.equals("layout")) {
            int open = this.nextSignificant(index);
            if (this.tokenAt(open).type != Token.TokenType.LEFT_PARENTHESIS) {
               return false;
            }

            StringBuilder sb = new StringBuilder();
            int close = open + 1;
            while (this.tokenAt(close).type != Token.TokenType.RIGHT_PARENTHESIS) {
               if (this.tokenAt(close).type == Token.TokenType.EOF) {
                  return false;
               }

               sb.append(this.tokenAt(close).value);
               close++;
            }

            layout = layout == null ? sb.toString() : layout + "," + sb;
            index = this.nextSignificant(close);
         } else if (INTERPOLATION_QUALIFIERS.contains(token.value)) {
            qualifiers.add(token.value);
            index = this.nextSignificant(index);
         } else if (PRECISION_QUALIFIERS.contains(token.value)) {
            index = this.nextSignificant(index);
         } else {
            break;
         }
      }

      String storage = this.tokenAt(index).value;
      int after = this.nextSignificant(index);
      return switch (storage) {
         case "uniform" -> this.parseUniform(after);
         case "in", "out" -> this.parseInterface(storage, after, qualifiers, layout);
         case "attribute" -> this.stage == GLSLParser.Stage.VERTEX && this.parseInterface("in", after, qualifiers, layout);
         case "varying" -> this.parseInterface(this.stage == GLSLParser.Stage.VERTEX ? "out" : "in", after, qualifiers, layout);
         default -> false;
      };
   }

   private int skipPrecision(int index) {
      while (this.tokenAt(index).type == Token.TokenType.IDENTIFIER && PRECISION_QUALIFIERS.contains(this.tokenAt(index).value)) {
         index = this.nextSignificant(index);
      }

      return index;
   }

   private int parseArraySize(int index, int[] size) {
      size[0] = 0;
      Token token = this.tokenAt(index);
      if (token.type != Token.TokenType.OPERATOR || !token.value.equals("[")) {
         return index;
      }

      int inner = this.nextSignificant(index);
      Token literal = this.tokenAt(inner);
      if (literal.type != Token.TokenType.LITERAL) {
         return -1;
      }

      try {
         size[0] = Integer.decode(literal.value.replaceAll("[uU]$", ""));
      } catch (NumberFormatException e) {
         return -1;
      }

      int close = this.nextSignificant(inner);
      if (this.tokenAt(close).type != Token.TokenType.OPERATOR || !this.tokenAt(close).value.equals("]")) {
         return -1;
      }

      return this.nextSignificant(close);
   }

   private boolean parseInterface(String ioType, int index, List<String> qualifiers, String layout) {
      index = this.skipPrecision(index);
      Token typeToken = this.tokenAt(index);
      if (typeToken.type != Token.TokenType.IDENTIFIER) {
         return false;
      }

      int nameIndex = this.nextSignificant(index);
      if (this.tokenAt(nameIndex).type == Token.TokenType.LEFT_BRACE) {
         return this.parseInterfaceBlock(ioType, typeToken.value, nameIndex, qualifiers);
      }

      if (this.tokenAt(nameIndex).type != Token.TokenType.IDENTIFIER) {
         return false;
      }

      List<Attribute> declared = new ArrayList<>();
      int[] arraySize = new int[1];
      while (true) {
         Token name = this.tokenAt(nameIndex);
         if (name.type != Token.TokenType.IDENTIFIER) {
            return false;
         }

         int next = this.parseArraySize(this.nextSignificant(nameIndex), arraySize);
         if (next < 0) {
            return false;
         }

         Attribute attribute = new Attribute(ioType, typeToken.value, name.value);
         attribute.setQualifiers(qualifiers);
         attribute.setArraySize(arraySize[0]);
         if (layout != null) {
            Matcher matcher = LOCATION.matcher(layout);
            if (matcher.find()) {
               attribute.explicitLocation = Integer.parseInt(matcher.group(1)) + declared.size();
            }
         }

         declared.add(attribute);
         Token separator = this.tokenAt(next);
         if (separator.type == Token.TokenType.COMMA) {
            nameIndex = this.nextSignificant(next);
         } else if (separator.type == Token.TokenType.SEMICOLON) {
            this.pos = next + 1;
            break;
         } else {
            return false;
         }
      }

      for (Attribute attribute : declared) {
         this.registerAttribute(attribute);
      }

      this.trimLineAfterDeclaration();
      return true;
   }

   private boolean parseInterfaceBlock(String ioType, String blockName, int braceIndex, List<String> qualifiers) {
      if (blockName.startsWith("gl_")) {
         return false;
      }

      boolean vertexOut = this.stage == GLSLParser.Stage.VERTEX && ioType.equals("out");
      boolean fragmentIn = this.stage == GLSLParser.Stage.FRAGMENT && ioType.equals("in");
      if (!vertexOut && !fragmentIn) {
         return false;
      }

      int slots = 0;
      int index = this.nextSignificant(braceIndex);
      int[] arraySize = new int[1];
      while (this.tokenAt(index).type != Token.TokenType.RIGHT_BRACE) {
         Token token = this.tokenAt(index);
         if (token.type != Token.TokenType.IDENTIFIER) {
            return false;
         }

         if (token.value.equals("layout")) {
            return false;
         }

         if (INTERPOLATION_QUALIFIERS.contains(token.value) || PRECISION_QUALIFIERS.contains(token.value)) {
            index = this.nextSignificant(index);
            continue;
         }

         String type = token.value;
         int nameIndex = this.nextSignificant(index);
         while (true) {
            if (this.tokenAt(nameIndex).type != Token.TokenType.IDENTIFIER) {
               return false;
            }

            int after = this.parseArraySize(this.nextSignificant(nameIndex), arraySize);
            if (after < 0) {
               return false;
            }

            slots += Attribute.slotCount(type, arraySize[0]);
            Token separator = this.tokenAt(after);
            if (separator.type == Token.TokenType.COMMA) {
               nameIndex = this.nextSignificant(after);
            } else if (separator.type == Token.TokenType.SEMICOLON) {
               index = this.nextSignificant(after);
               break;
            } else {
               return false;
            }
         }
      }

      StringBuilder body = new StringBuilder();
      for (int i = braceIndex + 1; i < index; i++) {
         body.append(this.tokens.get(i).value);
      }

      String instance = null;
      int after = this.nextSignificant(index);
      if (this.tokenAt(after).type == Token.TokenType.IDENTIFIER) {
         instance = this.tokenAt(after).value;
         after = this.parseArraySize(this.nextSignificant(after), arraySize);
         if (after < 0) {
            return false;
         }
      } else {
         arraySize[0] = 0;
      }

      if (this.tokenAt(after).type != Token.TokenType.SEMICOLON || slots == 0) {
         return false;
      }

      this.pos = after + 1;
      InterfaceBlock block = new InterfaceBlock(ioType, blockName, body.toString(), instance, arraySize[0], slots);
      block.setQualifiers(qualifiers);
      if (vertexOut) {
         block.setLocation(this.nextVaryingLocation);
         this.nextVaryingLocation += block.slotCount();
         this.vertOutBlocks.add(block);
      } else {
         InterfaceBlock vertexBlock = null;
         for (InterfaceBlock candidate : this.vertOutBlocks) {
            if (candidate.name.equals(blockName)) {
               vertexBlock = candidate;
            }
         }

         if (vertexBlock != null) {
            block.setLocation(vertexBlock.location);
         } else {
            block.setLocation(this.nextVaryingLocation);
            this.nextVaryingLocation += block.slotCount();
         }
      }

      this.appendNode(block);
      this.trimLineAfterDeclaration();
      return true;
   }

   private void registerAttribute(Attribute attribute) {
      switch (this.stage) {
         case VERTEX:
            if (attribute.ioType.equals("in")) {
               attribute.setLocation(-1);
               this.vertInAttributes.add(attribute);
            } else {
               attribute.setLocation(this.nextVaryingLocation);
               this.nextVaryingLocation += attribute.slotCount();
               this.vertOutAttributes.add(attribute);
            }
            break;
         case FRAGMENT:
            if (attribute.ioType.equals("in")) {
               Attribute vertAttribute = this.getVertAttribute(attribute);
               if (vertAttribute != null) {
                  attribute.setLocation(vertAttribute.location);
               } else {
                  attribute.setLocation(this.nextVaryingLocation);
                  this.nextVaryingLocation += attribute.slotCount();
               }

               this.fragInAttributes.add(attribute);
            } else {
               int location = attribute.explicitLocation >= 0 ? attribute.explicitLocation : this.nextFragOutLocation;
               attribute.setLocation(location);
               this.nextFragOutLocation = Math.max(this.nextFragOutLocation, location + attribute.slotCount());
               this.fragOutAttributes.add(attribute);
            }
      }

      this.appendNode(attribute);
   }

   private boolean parseUniform(int index) {
      index = this.skipPrecision(index);
      Token typeToken = this.tokenAt(index);
      if (typeToken.type != Token.TokenType.IDENTIFIER) {
         return false;
      }

      int next = this.nextSignificant(index);
      if (this.tokenAt(next).type == Token.TokenType.LEFT_BRACE) {
         return this.parseUniformBlock(typeToken.value, next);
      }

      Sampler.Type samplerType = Sampler.Type.fromName(typeToken.value);
      List<UniformBlock.Field> fields = new ArrayList<>();
      List<String> samplerNames = new ArrayList<>();
      int nameIndex = next;
      int[] arraySize = new int[1];

      while (true) {
         Token name = this.tokenAt(nameIndex);
         if (name.type != Token.TokenType.IDENTIFIER) {
            return false;
         }

         int after = this.parseArraySize(this.nextSignificant(nameIndex), arraySize);
         if (after < 0) {
            return false;
         }

         if (samplerType != null) {
            if (arraySize[0] != 0) {
               return false;
            }

            samplerNames.add(name.value);
         } else {
            fields.add(new UniformBlock.Field(typeToken.value, name.value, arraySize[0]));
         }

         if (this.tokenAt(after).type == Token.TokenType.OPERATOR && this.tokenAt(after).value.equals("=")) {
            if (samplerType != null) {
               return false;
            }

            int depth = 0;
            int initializerStart = after + 1;
            after++;
            while (true) {
               Token token = this.tokenAt(after);
               if (token.type == Token.TokenType.EOF) {
                  return false;
               }

               if (token.type == Token.TokenType.LEFT_PARENTHESIS || token.type == Token.TokenType.LEFT_BRACE) {
                  depth++;
               } else if (token.type == Token.TokenType.RIGHT_PARENTHESIS || token.type == Token.TokenType.RIGHT_BRACE) {
                  depth--;
               } else if (depth == 0 && (token.type == Token.TokenType.COMMA || token.type == Token.TokenType.SEMICOLON)) {
                  break;
               }

               after++;
            }

            StringBuilder initializer = new StringBuilder();
            for (int i = initializerStart; i < after; i++) {
               initializer.append(this.tokens.get(i).value);
            }

            fields.get(fields.size() - 1).setInitializer(initializer.toString());
         }

         Token separator = this.tokenAt(after);
         if (separator.type == Token.TokenType.COMMA) {
            nameIndex = this.nextSignificant(after);
         } else if (separator.type == Token.TokenType.SEMICOLON) {
            this.pos = after + 1;
            break;
         } else {
            return false;
         }
      }

      if (samplerType != null) {
         for (String name : samplerNames) {
            Sampler sampler = this.samplerMap.get(name);
            if (sampler == null) {
               sampler = new Sampler(samplerType, name);
               this.samplerMap.put(name, sampler);
               this.samplers.add(sampler);
            }

            this.appendNode(sampler);
         }

         this.trimLineAfterDeclaration();
      } else {
         this.legacyUniforms.addAll(fields);
      }

      return true;
   }

   private boolean parseUniformBlock(String blockName, int braceIndex) {
      UniformBlock block = new UniformBlock(blockName);
      int index = this.nextSignificant(braceIndex);
      int[] arraySize = new int[1];

      while (this.tokenAt(index).type != Token.TokenType.RIGHT_BRACE) {
         if (this.tokenAt(index).type == Token.TokenType.EOF) {
            return false;
         }

         while (this.tokenAt(index).type == Token.TokenType.IDENTIFIER && this.tokenAt(index).value.equals("layout")) {
            int close = this.nextSignificant(index);
            while (this.tokenAt(close).type != Token.TokenType.RIGHT_PARENTHESIS) {
               if (this.tokenAt(close).type == Token.TokenType.EOF) {
                  return false;
               }

               close++;
            }

            index = this.nextSignificant(close);
         }

         index = this.skipPrecision(index);
         Token type = this.tokenAt(index);
         if (type.type != Token.TokenType.IDENTIFIER) {
            return false;
         }

         int nameIndex = this.nextSignificant(index);
         while (true) {
            Token name = this.tokenAt(nameIndex);
            if (name.type != Token.TokenType.IDENTIFIER) {
               return false;
            }

            int after = this.parseArraySize(this.nextSignificant(nameIndex), arraySize);
            if (after < 0) {
               return false;
            }

            block.addField(new UniformBlock.Field(type.value, name.value, arraySize[0]));
            Token separator = this.tokenAt(after);
            if (separator.type == Token.TokenType.COMMA) {
               nameIndex = this.nextSignificant(after);
            } else if (separator.type == Token.TokenType.SEMICOLON) {
               index = this.nextSignificant(after);
               break;
            } else {
               return false;
            }
         }
      }

      int after = this.nextSignificant(index);
      if (this.tokenAt(after).type == Token.TokenType.IDENTIFIER) {
         block.setAlias(this.tokenAt(after).value);
         after = this.nextSignificant(after);
      }

      if (this.tokenAt(after).type != Token.TokenType.SEMICOLON) {
         return false;
      }

      this.pos = after + 1;
      UniformBlock existing = this.uniformBlockMap.get(block.name);
      if (existing != null) {
         block = existing;
      } else {
         this.uniformBlockMap.put(block.name, block);
         this.uniformBlocks.add(block);
      }

      this.appendNode(block);
      this.trimLineAfterDeclaration();
      return true;
   }

   private void trimLineAfterDeclaration() {
      Token next = this.tokenAt(this.pos);
      if (next.type == Token.TokenType.SPACING) {
         if (Objects.equals(next.value, "\n")) {
            this.pos++;
         } else {
            int i = next.value.indexOf("\n");
            if (i >= 0) {
               next.value = next.value.substring(i + 1);
            }
         }
      }
   }

   private Attribute getVertAttribute(Attribute attribute) {
      Attribute vertAttribute = null;

      for (Attribute attribute1 : this.vertOutAttributes) {
         if (Objects.equals(attribute1.id, attribute.id)) {
            vertAttribute = attribute1;
         }
      }

      return vertAttribute;
   }

   private void appendToken(Token token) {
      this.appendNode(GLSLParser.TokenNode.fromToken(token));
   }

   private void appendNode(GLSLParser.Node node) {
      this.getNodeStream().add(node);
   }

   private LinkedList<GLSLParser.Node> getNodeStream() {
      return switch (this.stage) {
         case VERTEX -> this.vsStream;
         case FRAGMENT -> this.fsStream;
      };
   }

   public String getOutput(GLSLParser.Stage stage) {
      StringBuilder stringBuilder = new StringBuilder();

      LinkedList<GLSLParser.Node> stream = switch (stage) {
         case VERTEX -> this.vsStream;
         case FRAGMENT -> this.fsStream;
      };
      Set<String> identifiers = stage == GLSLParser.Stage.VERTEX ? this.vertexIdentifiers : this.fragmentIdentifiers;
      GLSLParser.Node node = stream.getFirst();
      stringBuilder.append(node.getStringValue());
      stringBuilder.append("\n");
      if (stage == GLSLParser.Stage.VERTEX) {
         stringBuilder.append("#define gl_VertexID gl_VertexIndex\n");
         stringBuilder.append("#define gl_InstanceID gl_InstanceIndex\n\n");
      }

      stringBuilder.append("#define sampler sampler1\n");
      stringBuilder.append("#define sample sample1\n\n");

      for (Map.Entry<String, String> entry : LEGACY_FUNCTIONS.entrySet()) {
         if (identifiers.contains(entry.getKey())) {
            stringBuilder.append("#define %s %s\n".formatted(entry.getKey(), entry.getValue()));
         }
      }

      if (stage == GLSLParser.Stage.FRAGMENT && this.fragOutAttributes.isEmpty() && identifiers.contains("gl_FragColor")) {
         stringBuilder.append("layout(location = 0) out vec4 vk_FragColor;\n#define gl_FragColor vk_FragColor\n");
      }

      if (!this.legacyUniforms.isEmpty()) {
         stringBuilder.append("layout(binding = 0) uniform UBO {\n");

         for (UniformBlock.Field field : this.legacyUniforms) {
            stringBuilder.append("\t%s;\n".formatted(field.declaration()));
         }

         stringBuilder.append("};\n\n");
      }

      for (int i = 1; i < stream.size(); i++) {
         node = stream.get(i);
         stringBuilder.append(node.getStringValue());
      }

      return stringBuilder.toString();
   }

   public Map<String, UniformBlock.Field> getUniformDefaults() {
      Map<String, UniformBlock.Field> defaults = new HashMap<>();
      for (UniformBlock.Field field : this.legacyUniforms) {
         if (field.initializer != null) {
            defaults.put(field.getName(), field);
         }
      }

      return defaults;
   }

   public UBO[] createUBOs() {
      int uboCount = this.uniformBlockMap.size();
      int i = 0;
      UBO[] ubos;
      if (!this.legacyUniforms.isEmpty()) {
         uboCount++;
         i = 1;
         ubos = new UBO[uboCount];
         AlignedStruct.Builder builder = new AlignedStruct.Builder();

         for (UniformBlock.Field field : this.legacyUniforms) {
            builder.addUniform(Uniform.createUniformInfo(field.type, field.name, field.arraySize, true));
         }

         ubos[0] = builder.buildUBO("UBO 0", 0, Integer.MAX_VALUE);
         this.currentUniformLocation++;
      } else {
         ubos = new UBO[uboCount];
      }

      for (UniformBlock uniformBlock : this.uniformBlocks) {
         AlignedStruct.Builder builder = new AlignedStruct.Builder();

         for (UniformBlock.Field field : uniformBlock.fields) {
            builder.addUniform(Uniform.createUniformInfo(field.type, field.name, field.arraySize, true));
         }

         uniformBlock.setBinding(this.currentUniformLocation++);
         ubos[i] = builder.buildUBO(uniformBlock.name, uniformBlock.binding, Integer.MAX_VALUE);
         i++;
      }

      return ubos;
   }

   public List<ImageDescriptor> getSamplerList() {
      List<ImageDescriptor> imageDescriptors = new ObjectArrayList();
      int imageIdx = 0;

      for (Sampler sampler : this.samplers) {
         int descriptorType = switch (sampler.type) {
            case SAMPLER_2D, SAMPLER_CUBE -> 1;
            case I_SAMPLER_BUFFER -> 4;
         };
         sampler.setBinding(this.currentUniformLocation++);
         imageDescriptors.add(new ImageDescriptor(sampler.binding, "sampler2D", sampler.id, imageIdx, descriptorType));
         imageIdx++;
      }

      return imageDescriptors;
   }

   public interface Node {
      String getStringValue();
   }

   public enum Stage {
      VERTEX,
      FRAGMENT;
   }

   private static class Condition {
      boolean parentActive;
      boolean active;
      boolean taken;
      boolean known;
   }

   public static class TokenNode implements GLSLParser.Node {
      String type;
      String value;

      public TokenNode(String type, String value) {
         this.type = type;
         this.value = value;
      }

      public static GLSLParser.TokenNode fromToken(Token token) {
         return new GLSLParser.TokenNode(token.type.name(), token.value);
      }

      @Override
      public String getStringValue() {
         return this.value;
      }

      @Override
      public String toString() {
         return "GenericNode{type='" + this.type + "', value='" + this.value + "'}";
      }
   }
}
