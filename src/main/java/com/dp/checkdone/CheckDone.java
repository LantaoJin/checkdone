package com.dp.checkdone;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;

public class CheckDone implements Runnable{
    private final static Log LOG = LogFactory.getLog(CheckDone.class);
    
    private RollIdent ident;
    private boolean firstFlag;
    public CheckDone (RollIdent ident) {
        this.ident = ident;
        this.firstFlag = true;
    }
    
    @Override
    public String toString() {
        return "CheckDone [ident=" + ident.toString() + ", firstFlag=" + firstFlag + "]";
    }

    @Override
    public void run() {
        Calendar calendar = Calendar.getInstance();
        long nowTS = calendar.getTimeInMillis();
//        long nowTS = new Date(1376913788000l).getTime();
        while (ident.ts <= Util.getPrevWholeTs(nowTS, ident.period)) {
            LOG.info("Handling app " + ident.app + ", roll ts " + new Date(ident.ts).toString());
            if (!wasDone(ident)) {
                List<Path> dfsPathes = getRollHdfsPath(ident);
                try {
                    for (Path expectedFile : dfsPathes) {
                        if (!retryExists(expectedFile)) {
                            LOG.info("File " + expectedFile + " not ready.");
                            throw new IOException();
                        }
                    }
                    if (!retryTouch(dfsPathes.get(0).getParent())) {
                        LOG.warn("Failed to touch a done file. Try in next check cycle.");
                        break;
                    }
                    firstFlag = false;
                } catch (IOException e) {
                    if (!firstFlag && calendar.get(Calendar.MINUTE) >= alartTime) {
                        LOG.error("Alarm, too long to finish.");
                    }
                }
            }
            ident.ts = Util.getNextWholeTs(ident.ts, ident.period);
        }
    }
    
    public boolean wasDone (RollIdent ident) {
        String format  = Util.getFormatFromPeroid(ident.period);
        Date roll = new Date(ident.ts);
        SimpleDateFormat dm= new SimpleDateFormat(format);
        Path done =  new Path(hdfsbasedir + '/' + ident.app + '/' + 
                Util.getDatepathbyFormat(dm.format(roll)) + DONE_FLAG);
        return retryExists(done);
    }
    /*
     * Path format:
     * hdfsbasedir/appname/2013-11-01/14/08/machine01@appname_2013-11-01.14.08.gz.tmp
     * hdfsbasedir/appname/2013-11-01/14/08/machine02@appname_2013-11-01.14.08.gz.tmp
     */
    public List<Path> getRollHdfsPath (RollIdent ident) {
        List<Path> fileList = new ArrayList<Path>();
        String format  = Util.getFormatFromPeroid(ident.period);
        Date roll = new Date(ident.ts);
        SimpleDateFormat dm= new SimpleDateFormat(format);
        for (String source : ident.sources) {
            fileList.add(new Path(hdfsbasedir + '/' + ident.app + '/' + Util.getDatepathbyFormat(dm.format(roll)) + 
                    source + '@' + ident.app + "_" + dm.format(roll) + hdfsfilesuffix));
        }
        return fileList;
    }
    
    public boolean retryExists(Path expected) {
        for (int i = 0; i < REPEATE; i++) {
            try {
                return fs.exists(expected);
            } catch (IOException e) {
            }
            try {
                Thread.sleep(RETRY_SLEEP_TIME);
            } catch (InterruptedException ex) {
                return false;
            }
        }
        return false;
    }
    
    public boolean retryTouch(Path parentPath) {
        FSDataOutputStream out = null;
        Path doneFile = new Path(parentPath, DONE_FLAG);
        for (int i = 0; i < REPEATE; i++) {
            try {
                out = fs.create(doneFile);
                return true;
            } catch (IOException e) {
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        LOG.warn("Close hdfs out put stream fail!", e);
                    }
                }
            }
            try {
                Thread.sleep(RETRY_SLEEP_TIME);
            } catch (InterruptedException ex) {
                return false;
            }
        }
        return false;
    }
    /**
     * @param args
     */
    public static void main(String[] args) {
        try {
            init();
            for (RollIdent ident : rollIdents) {
                CheckDone checker = new CheckDone(ident);
                LOG.info("create a checkdone thread " + checker.toString());
                checkerThreadPool.scheduleWithFixedDelay(checker, 0, checkperiod, TimeUnit.SECONDS);
            }
        } catch (FileNotFoundException e) {
            LOG.error("Oops, got an exception.", e);
        } catch (NumberFormatException e) {
            LOG.error("Oops, got an exception.", e);
        } catch (IOException e) {
            LOG.error("Oops, got an exception.", e);
        }
    }

    static void init() throws FileNotFoundException, NumberFormatException, IOException {
        Properties prop = new Properties();
        prop.load(new FileReader(new File("checkdone.properties")));
        alartTime = Integer.parseInt(prop.getProperty("ALARM_TIME"));
        hdfsbasedir = prop.getProperty("HDFS_BASEDIR");
        if (hdfsbasedir.endsWith("/")) {
            hdfsbasedir = hdfsbasedir.substring(0, hdfsbasedir.length() - 1);
        }
        hdfsfilesuffix = prop.getProperty("HDFS_FILE_SUFFIX");
        checkperiod = Long.parseLong(prop.getProperty("CHECK_PERIOD", "300"));
        fillRollIdent(prop);
        String keytab = prop.getProperty("KEYTAB_FILE");
        String namenodePrincipal = prop.getProperty("NAMENODE.PRINCIPAL");
        String principal = prop.getProperty("PRINCIPAL");
        Configuration conf = new Configuration();
        conf.set("checkdone.keytab", keytab);
        conf.set("dfs.namenode.kerberos.principal", namenodePrincipal);
        conf.set("checkdone.principal", principal);
        conf.set("hadoop.security.authentication", "kerberos");
        UserGroupInformation.setConfiguration(conf);
        SecurityUtil.login(conf, "checkdone.keytab", "checkdone.principal");
        fs = (new Path(hdfsbasedir)).getFileSystem(conf);
        LOG.info("Create thread pool");
        checkerThreadPool = Executors.newScheduledThreadPool(Integer.parseInt(prop.getProperty("MAX_THREAD_NUM", "10")));
    }

    private static void fillRollIdent(Properties prop) {
        String apps = prop.getProperty("APPS");
        String[] appArray = apps.split(",");
        rollIdents = new ArrayList<RollIdent>();
        for (int i = 0; i < appArray.length; i++) {
            String appName;
            if ((appName = appArray[i].trim()).length() == 0) {
                continue;
            }
            RollIdent rollIdent = new RollIdent();
            rollIdent.app = appName;
            String[] hosts = prop.getProperty(appName + ".APP_HOSTS").split(",");
            List<String> sources = new ArrayList<String>();
            for (int j = 0; j < hosts.length; j++) {
                String host;
                if ((host = hosts[j].trim()).length() == 0) {
                    continue;
                }
                sources.add(host);
            }
            rollIdent.sources = sources;
            rollIdent.period = Long.parseLong(prop.getProperty(appName + ".ROLL_PERIOD"));
            rollIdent.ts = Long.parseLong(prop.getProperty(appName + ".BEGIN_TS", "1356969600000"));
            rollIdents.add(rollIdent);
        }
    }
    
    private static int alartTime;
    private static final String DONE_FLAG = "_done";
    private static String hdfsbasedir;
    private static String hdfsfilesuffix;
    private static FileSystem fs;
    private static final int REPEATE = 3;
    private static final int RETRY_SLEEP_TIME = 1000;
    private static long checkperiod;
    private static ScheduledExecutorService checkerThreadPool;
    private static List<RollIdent> rollIdents;
}
