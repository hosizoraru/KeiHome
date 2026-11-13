package os.kei.hyper.home.iconpack.perfect;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in, read-only Git HTTP diagnosis inside the target application's actual process. */
@RunWith(AndroidJUnit4.class)
public class PerfectNetworkDeviceTest {
  private static String packet(String value) {
    return String.format("%04x", value.getBytes(StandardCharsets.UTF_8).length + 4) + value;
  }
  @Test public void actualAppProcessCanReadGitAdvertisementAndPostLsRefs() throws Exception {
    assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("perfectNetworkProbe")));
    String base="https://github.com/pzcn/Perfect-Icons-Completion-Project.git/";
    JSONObject result=new JSONObject().put("uid",android.os.Process.myUid());
    JSONArray outcomes=new JSONArray();result.put("outcomes",outcomes);
    boolean allSucceeded=true;
    for(int mode=0;mode<4;mode++) {
      long start=System.currentTimeMillis();HttpURLConnection c=null;
      JSONObject outcome=new JSONObject().put("mode",mode);outcomes.put(outcome);
      try {
        URL url=new URL(base+(mode==0?"info/refs?service=git-upload-pack":"git-upload-pack"));
        c=(HttpURLConnection)(mode==2?url.openConnection(Proxy.NO_PROXY):url.openConnection());
        c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestProperty("User-Agent","git/2.50.1");c.setRequestProperty("Git-Protocol","version=2");
        if(mode==3)c.setRequestProperty("Connection","close");
        if(mode>0) {
          c.setRequestMethod("POST");c.setDoOutput(true);
          c.setRequestProperty("Content-Type","application/x-git-upload-pack-request");
          c.setRequestProperty("Accept","application/x-git-upload-pack-result");
          byte[] body=(packet("command=ls-refs\n")+packet("agent=git/2.50.1\n")+"0001"+packet("peel\n")+packet("symrefs\n")+packet("ref-prefix refs/heads/main\n")+"0000").getBytes(StandardCharsets.UTF_8);
          c.setFixedLengthStreamingMode(body.length);
          try(OutputStream out=c.getOutputStream()){out.write(body);}
        }
        int status=c.getResponseCode(),total=0;outcome.put("status",status);
        try(InputStream in=status==200?c.getInputStream():c.getErrorStream()) {
          if(in!=null){byte[] block=new byte[4096];int read;while((read=in.read(block))>=0){total+=read;if(total>2097152)throw new IOException("HTTP probe byte budget");}}
        }
        outcome.put("bytes",total);allSucceeded&=status==200;
      } catch(Exception failure) {
        allSucceeded=false;outcome.put("exception",failure.getClass().getName());outcome.put("message",String.valueOf(failure.getMessage()));
      } finally {
        outcome.put("durationMillis",System.currentTimeMillis()-start);
        if(c!=null)c.disconnect();
      }
    }
    File receipt=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"qa-perfect-network-http.json");
    try(FileOutputStream stream=new FileOutputStream(receipt)){stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));}
    System.out.println(result.toString());
    assertTrue("At least one App-process Git HTTP request failed; see qa-perfect-network-http.json",allSucceeded);
  }
}
