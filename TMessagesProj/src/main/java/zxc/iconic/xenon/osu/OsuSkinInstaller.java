package zxc.iconic.xenon.osu;

import android.app.Activity;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;
import zxc.iconic.xenon.NekoConfig;
import java.io.*;
import java.util.Locale;

public final class OsuSkinInstaller {
    private static final DispatchQueue QUEUE=new DispatchQueue("osu-skin-import");
    public static boolean supports(String name){return name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".osk");}
    public static void install(File source,Activity activity){
        QUEUE.postRunnable(()->{
            String key=null;
            try(InputStream input=new FileInputStream(source)){key=OsuSkinStore.importSkin(input);}catch(Exception error){FileLog.e(error);}
            String imported=key;
            AndroidUtilities.runOnUIThread(()->{
                if(imported!=null)NekoConfig.setOsuReplaySkin(imported);
                if(activity.isFinishing()||activity.isDestroyed())return;
                BaseFragment fragment=LaunchActivity.getSafeLastFragment();
                BulletinFactory factory=fragment!=null&&fragment.getParentActivity()==activity?BulletinFactory.of(fragment):BulletinFactory.global();
                if(imported==null)factory.createErrorBulletin(LocaleController.getString(R.string.OsuSkinImportFailed)).show();else factory.createSuccessBulletin(LocaleController.getString(R.string.OsuSkinImported)).show();
            });
        });
    }
}
