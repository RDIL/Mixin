/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered.org <http://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.launch;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.spongepowered.asm.mixin.MixinEnvironment;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;


/**
 * TweakClass for running mixins in production. Being a tweaker ensures that we get injected into the AppClassLoader but does mean that we will
 * need to inject the FML coremod by hand if running under FML.
 */
public class MixinTweaker implements ITweaker {
    
    /**
     * File containing this tweaker
     */
    private final File container;
    
    /**
     * If running under FML, we will attempt to inject any coremod specified in the metadata, FML's CoremodManager returns an ITweaker instance
     * which is the "handle" to the injected mod, we will need to proxy calls through to the wrapper. If initialisation fails (for example if we are
     * not running under FML or if an FMLCorePlugin key is not specified in the metadata) then this handle will be NULL and the tweaker will attempt
     * to start the Mixin subsystem automatically by looking for a MixinConfigs key in the jar metadata, this should be a comma-separated list of
     * mixin config JSON file names.
     */
    private final ITweaker fmlWrapper;
    
    /**
     * If FML init fails the tweaker will look for configs, if it finds any then it will set this flag to signify that it needs to inject the
     * transformer
     */
    private boolean injectTransformer;

    /**
     * Hello world
     */
    public MixinTweaker() {
        MixinBootstrap.init();
        this.container = this.findJarFile();
        this.fmlWrapper = this.initFMLCoreMod();
    }

    /**
     * Find and return the file containing this class
     */
    private File findJarFile() {
        URI uri = null;
        try {
            uri = this.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        }
        return uri != null ? new File(uri) : null;
    }

    /**
     * Attempts to initialise the FML coremod (if specified in the jar metadata)
     */
    @SuppressWarnings("unchecked")
    private ITweaker initFMLCoreMod() {
        try {
            String coreModName = this.getManifestAttribute("FMLCorePlugin");
            if (coreModName == null) {
                return null;
            }
            Class<?> coreModManager = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager");
            Method mdLoadCoreMod = coreModManager.getDeclaredMethod("loadCoreMod", LaunchClassLoader.class, String.class, File.class);
            mdLoadCoreMod.setAccessible(true);
            ITweaker wrapper = (ITweaker)mdLoadCoreMod.invoke(null, Launch.classLoader, coreModName, this.container);
            if (wrapper != null && "true".equalsIgnoreCase(this.getManifestAttribute("ForceLoadAsMod"))) {
                try {
                    Method mdGetLoadedCoremods = coreModManager.getDeclaredMethod("getLoadedCoremods");
                    List<String> loadedCoremods = (List<String>)mdGetLoadedCoremods.invoke(null);
                    loadedCoremods.remove(this.container.getName());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            return wrapper;
        } catch (Exception ex) {
//            ex.printStackTrace();
        }
        return null;
    }

    /* (non-Javadoc)
     * @see net.minecraft.launchwrapper.ITweaker#acceptOptions(java.util.List, java.io.File, java.io.File, java.lang.String)
     */
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (this.fmlWrapper == null) {
            String mixinConfigs = this.getManifestAttribute("MixinConfigs");
            if (mixinConfigs == null) {
                return;
            }
            
            for (String config : mixinConfigs.split(",")) {
                if (config.endsWith(".json")) {
                    this.injectTransformer = true;
                    MixinEnvironment.getCurrentEnvironment().addConfiguration(config);
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see net.minecraft.launchwrapper.ITweaker#injectIntoClassLoader(net.minecraft.launchwrapper.LaunchClassLoader)
     */
    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        if (this.fmlWrapper != null) {
            this.fmlWrapper.injectIntoClassLoader(Launch.classLoader);
        } else if (this.injectTransformer) {
            classLoader.registerTransformer(MixinBootstrap.TRANSFORMER_CLASS);
        }
    }

    /* (non-Javadoc)
     * @see net.minecraft.launchwrapper.ITweaker#getLaunchTarget()
     */
    @Override
    public String getLaunchTarget() {
        return "net.minecraft.client.main.Main";
    }

    /* (non-Javadoc)
     * @see net.minecraft.launchwrapper.ITweaker#getLaunchArguments()
     */
    @Override
    public String[] getLaunchArguments() {
        return new String[]{};
    }

    private String getManifestAttribute(String key) {
        if (this.container == null) {
            return null;
        }
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(this.container);
            Attributes manifestAttributes = jarFile.getManifest().getMainAttributes();
            return manifestAttributes.getValue(key);
        } catch (IOException ex) {
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ex) {
                    // this could be an issue later on :(
                }
            }
        }
        return null;
    }
}
