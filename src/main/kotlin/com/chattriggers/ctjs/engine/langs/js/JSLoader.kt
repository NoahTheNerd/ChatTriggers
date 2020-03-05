package com.chattriggers.ctjs.engine.langs.js

import com.chattriggers.ctjs.engine.loader.ILoader
import com.chattriggers.ctjs.engine.langs.Lang
import com.chattriggers.ctjs.engine.module.Module
import com.chattriggers.ctjs.engine.module.ModuleManager.modulesFolder
import com.chattriggers.ctjs.triggers.OnTrigger
import com.chattriggers.ctjs.utils.console.Console
import org.mozilla.javascript.*
import org.mozilla.javascript.Function
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider
import org.mozilla.javascript.commonjs.module.Require
import org.mozilla.javascript.commonjs.module.provider.StrongCachingModuleScriptProvider
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider
import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import java.net.URL

object JSLoader : ILoader {
    override var triggers = mutableListOf<OnTrigger>()
    override val toRemove = mutableListOf<OnTrigger>()
    override val console by lazy { Console(this) }

    private lateinit var moduleContext: Context
    private lateinit var evalContext: Context
    private lateinit var scope: Scriptable
    private lateinit var require: CTRequire
    private lateinit var ASMLib: Any

    private val INVOKE_JS_CALL = MethodHandles.lookup().findStatic(
        JSLoader::class.java,
        "asmInvoke",
        MethodType.methodType(Any::class.java, Callable::class.java, Array<Any?>::class.java)
    )

    override fun setup(jars: List<URL>) {
        if (Context.getCurrentContext() != null) {
            Context.exit()
        }

        instanceContexts(jars)

        val asmProvidedLibs = saveResource(
            "/js/asmProvidedLibs.js",
            File(modulesFolder.parentFile, "chattriggers-asm-provided-libs.js"),
            true
        )

        JSContextFactory.enterContext(moduleContext)

        try {
            moduleContext.evaluateString(
                scope,
                asmProvidedLibs,
                "asmProvided",
                1, null
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            console.printStackTrace(e)
        } finally {
            Context.exit()
        }
    }

    override fun asmSetup() {
        val asmLibFile = File(modulesFolder.parentFile, "chattriggers-asmLib.js")

        saveResource("/js/asmLib.js", asmLibFile, true)

        JSContextFactory.enterContext(moduleContext)

        try {
            val returned = require.loadCTModule("ASMLib", "ASMLib", asmLibFile.toURI())

            // Get the default export, the ASM Helper
            ASMLib = ScriptableObject.getProperty(returned, "default")
        } catch (e: Throwable) {
            e.printStackTrace()
            console.printStackTrace(e)
        } finally {
            Context.exit()
        }
    }

    override fun asmPass(module: Module, asmURI: URI) {
        if (Context.getCurrentContext() != null) {
            Context.exit()
        }

        JSContextFactory.enterContext(moduleContext)

        try {
            val returned = require.loadCTModule(module.name, module.metadata.asmEntry!!, asmURI)

            val asmFunction = ScriptableObject.getProperty(returned, "default") as? Function

            if (asmFunction == null) {
                console.out.println("Asm entry for module ${module.name} has an invalid export. " +
                        "An Asm entry must have a default export of a function.")
                return
            }

            ScriptableObject.putProperty(ASMLib, "currentModule", module.name)
            asmFunction.call(moduleContext, scope, scope, arrayOf(ASMLib))
        } catch (e: Throwable) {
            println("Error loading asm entry for module ${module.name}")
            e.printStackTrace()
            console.out.println("Error loading asm entry for module ${module.name}")
            console.printStackTrace(e)
        } finally {
            Context.exit()
        }
    }

    override fun entrySetup() {
        if (Context.getCurrentContext() != null) {
            Context.exit()
        }

        val moduleProvidedLibs = saveResource(
            "/js/moduleProvidedLibs.js",
            File(modulesFolder.parentFile, "chattriggers-modules-provided-libs.js"),
            true
        )

        JSContextFactory.enterContext(moduleContext)

        try {
            moduleContext.evaluateString(
                scope,
                moduleProvidedLibs,
                "moduleProvided",
                1, null
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            console.printStackTrace(e)
        } finally {
            Context.exit()
        }
    }

    override fun entryPass(module: Module, entryURI: URI) {
        if (Context.getCurrentContext() != null) {
            Context.exit()
        }

        JSContextFactory.enterContext(moduleContext)

        try {
            require.loadCTModule(module.name, module.metadata.entry!!, entryURI)
        } catch (e: Throwable) {
            println("Error loading module ${module.name}")
            e.printStackTrace()
            console.out.println("Error loading module ${module.name}")
            console.printStackTrace(e)
        } finally {
            Context.exit()
        }
    }

    override fun asmInvokeLookup(module: Module, functionURI: URI): MethodHandle {
        if (Context.getCurrentContext() != null) {
            Context.exit()
        }

        JSContextFactory.enterContext(moduleContext)

        try {
            val returned = require.loadCTModule(module.name, File(functionURI).name, functionURI)
            val func = ScriptableObject.getProperty(returned, "default") as Callable

            // When a call to this function ID is made, we always want to point it
            // to our asmInvoke method, which in turn should always call [func].
            return INVOKE_JS_CALL.bindTo(func)
        } catch (e: Throwable) {
            println("Error loading asm function $functionURI in module ${module.name}.")
            e.printStackTrace()
            console.out.println("Error loading asm function $functionURI in module ${module.name}.")
            console.printStackTrace(e)

            // If we can't resolve the target function correctly, we will return
            //  a no-op method handle that will always return null.
            //  It still needs to match the method type (Object[])Object, so we drop the arguments param.
            return MethodHandles.dropArguments(
                MethodHandles.constant(Any::class.java, null),
                0,
                Array<Any?>::class.java
            )
        } finally {
            Context.exit()
        }
    }

    @JvmStatic
    fun asmInvoke(func: Callable, args: Array<Any?>): Any? {
        val missingContext = Context.getCurrentContext() == null
        if (missingContext) {
            JSContextFactory.enterContext(moduleContext)
        }

        try {
            return func.call(moduleContext, scope, scope, args)
        } finally {
            if (missingContext) Context.exit()
        }
    }

    override fun eval(code: String): String? {
        JSContextFactory.enterContext(evalContext)
        try {
            return Context.toString(evalContext.evaluateString(scope, code, "<eval>", 1, null))
        } finally {
            Context.exit()
        }
    }

    override fun getLanguage() = Lang.JS

    override fun trigger(trigger: OnTrigger, method: Any, args: Array<out Any?>) {
        if (Context.getCurrentContext() == null) JSContextFactory.enterContext()

        try {
            if (method !is Function)
                throw ClassCastException("Need to pass actual function to the register function, not the name!")

            method.call(Context.getCurrentContext(), scope, scope, args)
        } catch (e: Throwable) {
            console.printStackTrace(e)
            removeTrigger(trigger)
        }
    }

    private fun instanceContexts(files: List<URL>) {
        JSContextFactory.addAllURLs(files)

        moduleContext = JSContextFactory.enterContext()
        scope = ImporterTopLevel(moduleContext)

        val sourceProvider = UrlModuleSourceProvider(listOf(modulesFolder.toURI()), listOf())
        val moduleProvider = StrongCachingModuleScriptProvider(sourceProvider)
        require = CTRequire(moduleProvider)
        require.install(scope)

        Context.exit()

        JSContextFactory.optimize = false
        evalContext = JSContextFactory.enterContext()
        Context.exit()
        JSContextFactory.optimize = true
    }

    class CTRequire(moduleProvider: ModuleScriptProvider) : Require(moduleContext, scope, moduleProvider, null, null, false) {
        fun loadCTModule(name: String, entry: String, uri: URI): Scriptable {
            return getExportedModuleInterface(moduleContext, name + File.separator + entry, uri, null, false)
        }
    }
}