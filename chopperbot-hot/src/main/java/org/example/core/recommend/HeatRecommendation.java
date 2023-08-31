package org.example.core.recommend;

/**
 * @author Genius
 * @date 2023/07/22 23:20
 **/

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.util.TypeUtils;
import org.example.bean.Live;
import org.example.cache.FileCacheManagerInstance;
import org.example.config.FollowDog;
import org.example.config.HotModuleConfig;
import org.example.config.HotModuleSetting;
import org.example.constpool.PluginName;
import org.example.core.HotModuleDataCenter;
import org.example.core.creeper.loadconfig.*;
import org.example.core.factory.BarrageLoadConfigFactory;
import org.example.core.factory.LiveLoadConfigFactory;
import org.example.core.taskcenter.request.ReptileRequest;
import org.example.core.taskcenter.task.TaskRecord;
import org.example.init.InitPluginRegister;
import org.example.log.ChopperLogFactory;
import org.example.log.LoggerType;
import org.example.plugin.CommonPlugin;
import org.example.plugin.GuardPlugin;
import org.example.core.taskcenter.TaskCenter;
import org.example.plugin.PluginCheckAndDo;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 根据热门的直播以及看门狗设置来推荐热门直播间到系统
 */
public class HeatRecommendation extends GuardPlugin {

    private Map<String, List<FollowDog>> platformFollowDogMap ;   //每个平台的跟风列表

    private BlockingQueue<String> hotEventList; //用于接收每一次的热榜更新信息，并进行跟风狗推送

    private static final String SHUTDOWN_SIGN = "shutdown";

    public HeatRecommendation(String module, String pluginName, List<String> needPlugins, boolean isAutoStart) {
        super(module, pluginName, needPlugins, isAutoStart);
    }

    @Override
    public boolean init() {
        try {
            platformFollowDogMap = new ConcurrentHashMap<>();
            hotEventList = new ArrayBlockingQueue<>(1024);
            List<HotModuleSetting> modules = new ArrayList<>();

            JSONArray jsonModules = (JSONArray) FileCacheManagerInstance
                    .getInstance()
                    .getFileCache(HotModuleConfig.getFullFilePath())
                    .get("Module");

            jsonModules.forEach(jsonModule->{
                modules.add(JSONObject.parseObject(jsonModule.toString(),HotModuleSetting.class));
            });

            for (HotModuleSetting module : modules) {
                if (module.isFollowDogEnable()) {
                    platformFollowDogMap.put(module.getPlatform(),module.getFollowDogs());
                }
            }
        }catch (Exception e){
            return false;
        }
        return super.init();
    }

    @Override
    public void start() {
        try {
            String platform = hotEventList.poll(5,TimeUnit.MILLISECONDS);
            if(SHUTDOWN_SIGN.equals(platform)){
                return;
            }
            if(platform!=null){
                List<FollowDog> followDogList;
                ChopperLogFactory.getLogger(LoggerType.Hot).info("<{}> Hotspot event detected.", PluginName.HOT_RECOMMENDATION_PLUGIN);
                if(platformFollowDogMap.containsKey(platform)
                        &&(followDogList=platformFollowDogMap.get(platform)).size()>0){
                    //发送给爬虫队列
                    for (FollowDog followDog : followDogList) {
                        String moduleName = followDog.getModuleName();
                        List<? extends Live> lives;
                        if(FollowDog.ALL_LIVES.equals(moduleName)){
                            lives = HotModuleDataCenter.DataCenter().getLiveList(platform);
                        }else{
                            lives = HotModuleDataCenter.DataCenter().getModule(platform,moduleName).getHotLives();
                        }
                        //TODO 待完善 1，需要发送弹幕爬虫请求 2，callback更改
                        for (Live live : needRecommend(lives, followDog.getBanLiver(), followDog.getTop())) {
                            String tempPlatform = live.getPlatform();
                            this.info(String.format("推荐请求:平台 %s,直播间 %s,主播 %s",tempPlatform,live.getLiveId(),live.getLiver()));
                            new DouyuLiveLoadBarrageConfig(live.getLiver(),String.valueOf(live.getLiveId()));
                            LoadLiveConfig loadLiveConfig = LiveLoadConfigFactory.buildLiveConfig(
                                    tempPlatform, live.getLiveId(), live.getLiver(),
                                    true, true);

//                            LoadBarrageConfig loadBarrageConfig = BarrageLoadConfigFactory.buildBarrageConfig(tempPlatform,
//                                    live.getLiver(), live.getLiveId());
                            PluginCheckAndDo.CheckAndDo(
                                    plugin -> {
                                        ((TaskCenter)plugin).request(new ReptileRequest(loadLiveConfig,(t)->{
                                            System.out.println(String.format("%s 文件已保存", t));
                                        }));
//                                        ((TaskCenter)plugin).request(new ReptileRequest(loadBarrageConfig,(t)->{
//                                            System.out.println(String.format("%s 爬虫任务已结束", live.getLiver()));
//                                        }));
                                    },
                                    PluginName.TASK_CENTER_PLUGIN
                            );
                            CommonPlugin plugin = InitPluginRegister.getPlugin(PluginName.TASK_CENTER_PLUGIN);
                        }
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if(hotEventList.isEmpty()){
            hotEventList.offer(SHUTDOWN_SIGN);
        }
    }

    /**
     * 查看热门直播，对比banliver名单，选取前top个名额进行推送推荐名单
     * @param lives
     * @param banLivers
     * @param top
     * @return
     */
    private List<Live> needRecommend(List<? extends Live> lives,List<String> banLivers,int top){
        List<Live> recommendLive = new ArrayList<>();
        List<String> livers = new ArrayList<>();
        int num = 0;

        for (Live live : lives) {
            String liver = live.getLiver();
            if (!isBan(liver,banLivers)) {
                recommendLive.add(live);
                livers.add(liver);
                num++;
            }
            if(num>=top)break;
        }
        ChopperLogFactory.getLogger(LoggerType.Hot).info("<HeatRecommend> recommend {} lives,liver info:",livers);
        return recommendLive;
    }

    private boolean isBan(String liver,List<String> banLivers){
        for (String banLiver : banLivers) {
            if (Pattern.compile(banLiver).matcher(liver).find()) {
                return true;
            }
        }
        return false;
    }

    public void sendHotEvent(String platform){
        hotEventList.offer(platform);
    }

    public static void main(String[] args) {
        System.out.println(TypeUtils.isProxy(TaskRecord.class));
    }
}