package com.afs.host.plugins;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import com.afs.pluginapi.HostTrust;
import com.afs.pluginapi.PluginProtocol;
import com.afs.guard.BuildConfig;

/** Read-only verification against real installed signing histories; never seeds or clears user data. */
public final class PluginSigningDeviceTest extends Instrumentation {
    private int checks;
    private void check(boolean value,String label){
        if(!value)throw new AssertionError(label);
        checks++;android.util.Log.i("AFSPluginSigningQA","PASS "+label);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Context c=getTargetContext();PackageManager pm=c.getPackageManager();
            String expected=BuildConfig.PLUGIN_TRUST_SHA256;
            String plugin="com.afs.plugin.usagestats";
            PackageInfo info=pm.getPackageInfo(plugin,PackageManager.GET_SIGNING_CERTIFICATES);
            check(info.signingInfo.getSigningCertificateHistory().length>1,"plugin has genuine rotation history");
            check(!info.signingInfo.hasMultipleSigners(),"rotation is not concurrent co-signing");
            check(expected.equals(PluginIdentity.read(c,plugin).signerSha256),"host identifies current plugin release signer");
            check(PluginIdentity.trusted(c,PluginIdentity.read(c,plugin).signerSha256),"current plugin release signer is trusted");
            check(expected.equals(HostTrust.signerSha256(c,PluginProtocol.HOST_PACKAGE)),"plugin identifies current host release signer");
            check(HostTrust.verify(c,PluginProtocol.HOST_PACKAGE,expected,false),"production host certificate pin matches");
            check(!HostTrust.verify(c,PluginProtocol.HOST_PACKAGE,"wrong-pin",false),"wrong certificate pin remains rejected");
            check(pm.checkPermission(PluginProtocol.PERMISSION,plugin)==PackageManager.PERMISSION_GRANTED,"signature IPC permission granted after rotation");
            PluginRegistry registry=HostRuntime.get(c).registry();registry.refreshNow();
            PluginEntry entry=registry.findByPackage(plugin);
            check(entry!=null&&!entry.hasError()&&entry.compatible,"plugin discovery accepts migrated installation");
            result.putString("stream","Plugin signing: "+checks+" checks passed\n");finish(-1,result);
        }catch(Throwable failure){
            result.putString("stream","Plugin signing FAILED: "+failure+"\n");finish(0,result);
        }
    }
}
