# ColorOS runtime artwork inventory

Source: the inspected ColorOS AVD on 2026-11-03. Native download requests covered 1,006 valid package names from the morph whitelist and the resource database. All 575 published package resources were captured without installing their APKs; 431 requested names currently have no published redraw.

This is an inventory of available source files, not a promise that every application has every style. Night and monochrome are independent. The converter validates actual masks, excludes dynamic source artwork where required, and keeps original app adaptations ahead of downloaded fallback. Shortcut-only files do not become launcher icons.

Columns describe source presence. 1x2, 2x1 and 2x2 are retained for source completeness; the current desktop conversion uses the standard 1x1 artwork. `Other` lists material (`mat`), pebble (`peb`), daylight (`day`), night shadow (`nsd`), outline and meow (`cat`) resources when present; their presence does not imply an exposed unsupported style.

| Package | Day foreground | Night | Monochrome | Other | Size variants | Images |
| --- | --- | --- | --- | --- | --- | ---: |
| `ae.uaepass.mainapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `air.tv.douyu.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `andes.oplus.documentsreader` | Yes | Yes | Yes | day, nsd | 1x2, 2x1, 2x2 | 18 |
| `app.source.getcontact` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `cmb.pb` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `cn.TuHu.android` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `cn.com.chsi.chsiapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.com.cmbc.newmbank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.com.hkgt.gasapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.com.pansky.sxzgyl` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.com.spdb.mobilebank.per` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.damai` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `cn.gov.chinatax.gt4.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.gov.pbc.dcep` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.gov.tax.its` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.hsa.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.jj` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `cn.jthj.turbobunny` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `cn.kuwo.player` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `cn.ninegame.gamemanager` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.soulapp.android` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 18 |
| `cn.wenyu.bodian` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.wps.moffice.lite` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `cn.wps.moffice_eng` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `cn.xuexi.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.A17zuoye.mobile.homework` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.MobileTicket` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.Qunar` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.Slack` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.UCMobile` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.achievo.vipshop` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.agoda.mobile.consumer` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.ahrcu.mobilebank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ai.marki` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ai.obc.cbn.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.airbnb.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.alibaba.ailabs.tg` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.alibaba.aliexpresshd` | Yes | Yes | Yes | mat, outline, cat | 1x2, 2x1, 2x2 | 19 |
| `com.alibaba.android.rimet` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.alibaba.wireless` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.amazon.avod.thirdpartyclient` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.amazon.kindle` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.amazon.mShop.android.shopping` | Yes | Yes | Yes | mat, outline, cat | 1x2, 2x1, 2x2 | 19 |
| `com.amazon.mp3` | Yes | Yes | Yes | mat, cat | 1x2, 2x1, 2x2 | 18 |
| `com.android.bankabc` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.android.bluetooth` | — | — | — | — | — | 1 |
| `com.android.chrome` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.android.clock.sd` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.android.contacts` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 41 |
| `com.android.email` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.android.incallui` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 32 |
| `com.android.launcher` | Yes | Yes | Yes | mat, peb | — | 6 |
| `com.android.mms` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 22 |
| `com.android.permissioncontroller` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.android.phone` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.android.settings` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 26 |
| `com.android.stk` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.android.vending` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.android.wifi.dialog` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.anjuke.android.app` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.apple.android.music` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.application.zomato` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.audible.application` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.autonavi.minimap` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 26 |
| `com.azure.authenticator` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.babycloud.hanju` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.BaiduMap` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.baidu.appsearch` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.baidu.baidutranslate` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.duer.superapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.baidu.haokan` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.homework` | Yes | Yes | Yes | mat, nsd, outline | 1x2, 2x1, 2x2 | 19 |
| `com.baidu.input` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.input_oppo` | Yes | Yes | Yes | — | — | 3 |
| `com.baidu.netdisk` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.baidu.newapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.baidu.searchbox` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.baidu.searchbox.lite` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.searchbox.tomas` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.tieba` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.baidu.wenku` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.bankcomm.Bankcomm` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.bankcomm.maidanba` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.bee.weathesafety` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 18 |
| `com.bilibili.app.in` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.bitmango.go.bubblepop` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.block.juggle` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 18 |
| `com.bokecc.dance` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.booking` | Yes | Yes | Yes | mat, cat | 1x2, 2x1, 2x2 | 18 |
| `com.bytedance.ls.merchant` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.cainiao.wireless` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.calm.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.canva.editor` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.cat.readall` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.ccb.longjiLife` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.cctv.yangshipin.app.androidp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.cebbank.mobile.cemb` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.chaoxing.mobile` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.chaozh.iReader.dj` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.chase.sig.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.chif.weather` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.chinalife.ebz` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.chinamobile.mcloud` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.chinamworld.bocmbci` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.chinamworld.main` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.chinatelecom.bestpayclient` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.cib.cibmb` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.citiccard.mobilebank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.clover.daysmatter` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.cmbchina.ccd.pluto.cmbActivity` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.cmcc.cmvideo` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.cmri.universalapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.coloros.alarmclock` | Yes | Yes | Yes | mat, peb, day, nsd, outline | 2x2 | 15 |
| `com.coloros.backuprestore` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.calculator` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.calendar` | Yes | Yes | Yes | mat, peb, day, nsd | — | 10 |
| `com.coloros.compass2` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.digitalwellbeing` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 33 |
| `com.coloros.familyguard` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.filemanager` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 22 |
| `com.coloros.findmyphone` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.gallery3d` | Yes | Yes | Yes | mat, peb, day, nsd, outline | 1x2, 2x1, 2x2 | 22 |
| `com.coloros.musiclink` | Yes | — | Yes | mat, peb, day, nsd | — | 7 |
| `com.coloros.note` | Yes | Yes | Yes | mat, peb, day, nsd, outline | 1x2, 2x1, 2x2 | 23 |
| `com.coloros.onekeylockscreen` | Yes | — | Yes | mat, peb | — | 5 |
| `com.coloros.operationManual` | Yes | Yes | Yes | — | — | 3 |
| `com.coloros.phonemanager` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.shortcuts` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.soundrecorder` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.translate` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 20 |
| `com.coloros.video` | Yes | — | Yes | mat, peb, day, nsd | — | 7 |
| `com.coloros.weather.service` | Yes | — | — | mat | — | 3 |
| `com.coloros.weather2` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.contextlogic.wish` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.coolapk.market` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.cs_credit_bank` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.csii.mobilebank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ct.client` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.cubic.autohome` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 19 |
| `com.cyclotron.in` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.dayunlinks.cloudbirds` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.deepseek.chat` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.depreseclipse.avert` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.devsisters.cookieadventure` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.dianping.v1` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.didapinche.booking` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.discord` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.doordash.driverapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.dragon.read` | Yes | Yes | Yes | mat, peb, nsd, outline, cat | 1x2, 2x1, 2x2 | 21 |
| `com.dropbox.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.dts.freefireth` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.duoduo.child.story` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.duolingo` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.duowan.kiwi` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.duowan.mobile` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.dw.btime` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 18 |
| `com.dz.hmjc` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.eastmoney.android.berlin` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ebay.mobile` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.ecitic.bank.mobile` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ecolog.gtsaugus` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.eduyun.app.factory.ykt` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.eg.android.AlipayGphone` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 34 |
| `com.eggflower.read` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.einnovation.temu` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.epi` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.evernote` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ex.bfxtg.nearme.gamecenter` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 18 |
| `com.example.businesshall` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.excean.dualaid` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.facebook.katana` | Yes | Yes | Yes | mat, cat | 1x2, 2x1, 2x2 | 24 |
| `com.facebook.lite` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.facebook.orca` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.fenbi.android.leo` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.fenbi.android.solar` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.fenqile.fenqile` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.fido.asm` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.figma.mirror` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.finshell.fin` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.finshell.wallet` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 21 |
| `com.fitbit.FitbitMobile` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.flipkart.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.fullmetalgamedev.aquablockspuzzleseas` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 18 |
| `com.fullmetalgamedev.fruitshooting` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 18 |
| `com.fuzhoudream.haveskineveryday` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.games.yikegames` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.ganji.android` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.ganji.android.haoche_c` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.getsomeheadspace.android` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.gh.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.glodon.drawingexplorer` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.gojek.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.GoogleCamera` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.adm` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.bard` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.chromecast.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.docs` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.fitness` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.magazines` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.maps` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.messaging` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.nbu.files` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.google.android.apps.nbu.paisa.user` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.photos` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.podcasts` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.subscriptions.red` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.tachyon` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.translate` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.apps.youtube.music` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.google.android.calendar` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.gm` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.gms` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.google.android.googlequicksearchbox` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.google.android.keep` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.marvin.talkback` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.projection.gearhead` | Yes | — | — | mat | — | 3 |
| `com.google.android.videos` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.google.android.youtube` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.gorgeous.lite` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.gotokeep.keep` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.grabtaxi.passenger` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.greenpoint.android.mc10086.activity` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.grubhub.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.gstarmc.android` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.handsgo.jiakao.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.hanweb.android.sdzwfw.activity` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.hanweb.android.zhejiang.activity` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.happyelements.AndroidAnimal` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.hexin.plat.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.heytap.browser` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.heytap.cloud` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 21 |
| `com.heytap.health` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.heytap.market` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.heytap.music` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.heytap.quicksearchbox` | Yes | Yes | Yes | mat, peb, day, nsd | — | 8 |
| `com.heytap.reader` | Yes | Yes | Yes | mat, peb, day, nsd, cat | 1x2, 2x1, 2x2 | 25 |
| `com.heytap.speechassist` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.heytap.themestore` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 23 |
| `com.heytap.yoli` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.hicorenational.antifraud` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.higgsltd.higgsslot` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.hnnx.eBank.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.hnnx.pmbank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.hpbr.bosszhipin` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.huawei.appmarket` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.huawei.health` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.huawei.hwid` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.huawei.smarthome` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.huaxiaozhu.rider` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.hunantv.imgo.activity` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.hupu.shihuo` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.icbc` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.ideaflow.zmcy` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.iflytek.elpmobile.smartlearning` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.iflytek.inputmethod` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.iflytek.oshall.ahzwfw` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.immomo.momo` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.instagram.android` | Yes | Yes | Yes | mat, outline, cat | 1x2, 2x1, 2x2 | 20 |
| `com.instagram.barcelona` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.instagram.lite` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.intsig.camscanner` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jco.aiview` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jiaohua_browser` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jieyatzhj02.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.jingdong.app.mall` | Yes | Yes | Yes | mat, nsd, outline, cat | 1x2, 2x1, 2x2 | 21 |
| `com.jiongji.andriod.card` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jjwxc.reader` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.job.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jooan.qiaoanzhilian` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jsmcc` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.juanvision.EseeNetProj` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.julanling.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jxnxs.mobile.bank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.jzzs.ParentsHelper` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.kiloo.subwaysurf` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.kingpoint.gmcchh` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.kmxs.reader` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.kuaikan.comic` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.kuaishou.nebula` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.kuaiyin.player` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.kugou.android.lite` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.kugou.android.ringtone` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.kwai.theater` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.kwai.video` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.kwai.videoeditor` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.larus.nova` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.lazada.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.lemon.lv` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.lemon.lvoverseas` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.lianjia.beike` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.life360.android.safetymapd` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.lingan.seeyou` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.lingyue.zebraloan` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.linkedin.android` | Yes | Yes | Yes | mat, cat | 1x2, 2x1, 2x2 | 18 |
| `com.lion.market` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.lofter.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.lu.ashionweather` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ludo.king` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.luna.music` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.m4399.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mampod.ergedd` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.masseffect.suki` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.max.xiaoheihe` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.meelive.ingkee` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.meitu.meiyancamera` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.meitu.wink` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mendon.riza` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.meta.box` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.meta.box.assist64` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mi.health` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.miHoYo.Yuanshen` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.miHoYo.hkrpg` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 18 |
| `com.microsoft.appmanager` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.microsoft.emmx` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.microsoft.office.excel` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.microsoft.office.officehubrow` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.microsoft.office.onenote` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.microsoft.office.outlook` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.microsoft.office.powerpoint` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.microsoft.office.word` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.microsoft.skydrive` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.microsoft.teams` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.microsoft.todos` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.midea.ai.appliances` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mihoyo.hyperion` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.minitech.miniworld.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 18 |
| `com.miravia.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mm.android.lc` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mobile.legends` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.mojang.minecraftpe` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.moji.mjweather` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.moonshot.kimichat` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mosheng` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.msxf.ayh` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mt.mtxx.mtxx` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.mxtech.videoplayer.ad` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.myfitnesspal.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.mygolbs.mybus` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.myntra.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.nearme.gamecenter` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 22 |
| `com.nearme.gamecenter.ddz.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.nearme.gamercenter` | — | — | — | — | — | 2 |
| `com.nearme.instant.platform` | Yes | — | Yes | mat, peb, day, nsd | — | 7 |
| `com.netease.cloudmusic` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.netease.dwrg.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.netease.gl` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.netease.mail` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.netease.mc.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.netease.mobimail` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.netease.party.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.netease.sky.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.netease.uu` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.netease.yhtj.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.netflix.mediaclient` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.neuqsoft.newggfwmobile` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.newleaf.app.android.victor` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.niksoftware.snapseed` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.njh.biubiu` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.nxy.sc` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.okcam.ookcam` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.olacabs.customer` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oneplus.backuprestore` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oneplus.bbs` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oneplus.brickmod` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oneplus.brickmode` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oneplus.calculator` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oneplus.filemanager` | Yes | Yes | Yes | mat, peb, day, nsd, outline | 1x2, 2x1, 2x2 | 21 |
| `com.oneplus.gallery` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oneplus.mall` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.oneplus.note` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oneplus.soundrecorder` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oneplus.store` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.openai.chatgpt` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oplus.ai.magicstudio` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oplus.aimemory` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oplus.camera` | Yes | Yes | Yes | mat, peb, day, nsd, outline | 1x2, 2x1, 2x2 | 28 |
| `com.oplus.consumerIRApp` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oplus.games` | Yes | Yes | Yes | mat, peb | 1x2, 2x1, 2x2 | 18 |
| `com.oplus.logtrial` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oplus.member` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oplus.phonemanager` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oplus.play` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `com.oplus.themestore` | Yes | — | Yes | mat, peb | — | 5 |
| `com.oplus.tips` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.oplus.vip` | Yes | — | Yes | mat, peb | 1x2, 2x1, 2x2 | 14 |
| `com.oppo.community` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 24 |
| `com.oppo.store` | Yes | Yes | Yes | mat, peb, nsd | 1x2, 2x1, 2x2 | 21 |
| `com.outfit7.talkingtomgoldrun.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.p1.mobile.putong` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 18 |
| `com.pandora.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.parallel.odyssey` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.particlenews.newsbreak` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.pbyw.wadmm.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.phoenix.read` | Yes | Yes | Yes | nsd, cat | 1x2, 2x1, 2x2 | 18 |
| `com.phonepe.app` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.picsart.studio` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.pingan.carowner` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.pingan.lifeinsurance` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.pingan.paces.ccms` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.pinterest` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.postmates.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.pubg.imobile` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.qidian.QDReader` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.qihoo.appstore` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.qihoo.cleandroid_cn` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.qihoo.namiso` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.qiyi.video` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.qiyi.video.lite` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.qpon.platform` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.qq.reader` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.qqgame.happymj` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.qqgame.hlddz` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.quark.browser` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.reddit.frontpage` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.redteamobile.roaming` | Yes | Yes | Yes | day, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.roblox.client` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sankuai.meituan` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 27 |
| `com.sankuai.meituan.dispatch.crowdsource` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.sankuai.meituan.meituanwaimaibusiness` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.sankuai.meituan.merchant` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sankuai.meituan.takeoutnew` | Yes | Yes | Yes | mat, nsd, outline | 1x2, 2x1, 2x2 | 19 |
| `com.sankuai.movie` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sankuai.youxuan` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sdu.didi.gsui` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.sdu.didi.psnger` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.seetong.app.seetong` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.seewo.easicare.pro` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.sf.activity` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sgcc.wsgw.cn` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.shopee.id` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.shoujiduoduo.ringtone` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.shuqi.controller` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 18 |
| `com.sina.weibo` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 24 |
| `com.sinovatech.unicom.ui` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.sinyee.babybus.recommendapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.smile.gifmaker` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.snapchat.android` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.snda.lantern.wifilocating` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.snda.wifilocating` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.sofunny.Sausage` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.sohu.inputmethod.sogou` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.sohu.newsclient` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sohu.sohuvideo` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.spotify.music` | Yes | Yes | Yes | mat, cat | 1x2, 2x1, 2x2 | 18 |
| `com.squareup.cash` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ss.android.article.daziban` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ss.android.article.lite` | Yes | Yes | Yes | mat, nsd, outline | 1x2, 2x1, 2x2 | 19 |
| `com.ss.android.article.news` | Yes | Yes | Yes | mat, nsd, outline, cat | 1x2, 2x1, 2x2 | 21 |
| `com.ss.android.lark` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ss.android.ugc.aweme` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 27 |
| `com.ss.android.ugc.aweme.lite` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.ss.android.ugc.livelite` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ss.android.yumme.video` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.ssimulator.net.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.strava` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.sup.android.superb` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.supercell.clashofclans` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 18 |
| `com.taobao.etao` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.taobao.idlefish` | Yes | Yes | Yes | mat, nsd, outline, cat | 1x2, 2x1, 2x2 | 20 |
| `com.taobao.litetao` | Yes | Yes | Yes | mat, peb, nsd, outline | 1x2, 2x1, 2x2 | 20 |
| `com.taobao.live` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.taobao.taobao` | Yes | Yes | Yes | mat, nsd, outline, cat | 1x2, 2x1, 2x2 | 21 |
| `com.taobao.trip` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 18 |
| `com.taptap` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.teamtalk.im` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 19 |
| `com.tencent.KiHan` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.android.qqdownloader` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.tencent.androidqqmail` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.apps.valorant` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.tencent.gamehelper.pg` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.tencent.hunyuan.app.chat` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.tencent.ig` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.jkchess` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.karaoke` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.tencent.lolm` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.map` | Yes | Yes | Yes | mat, nsd, outline | 1x2, 2x1, 2x2 | 19 |
| `com.tencent.mm` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 27 |
| `com.tencent.mobileqq` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 22 |
| `com.tencent.news` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.tencent.qqlive` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.tencent.qqmusic` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.tencent.qqpimsecure` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.tencent.tmgp.cf` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.tmgp.cod` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.tmgp.dfm` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.tmgp.supercell.clashofclans` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.weishi` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.tencent.wemeet.app` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.tencent.wetype` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.tick.service` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 19 |
| `com.tmall.wireless` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.tongcheng.android` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.tuyoo.doudizhu.android3d.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.twitter.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 18 |
| `com.ubercab` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.ubercab.eats` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.ucmobile.lite` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.unionpay` | Yes | Yes | Yes | mat, outline | 1x2, 2x1, 2x2 | 18 |
| `com.upgadata.up7723` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.videogo` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.vivo.easyshare` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.walmart.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.waze` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.wepie.snake.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.wepie.weplay` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.woodleaves.read` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.wuba` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.wudaokou.hippo` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.xb.topnews` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.xhey.xcamera` | Yes | Yes | Yes | nsd, outline | 1x2, 2x1, 2x2 | 18 |
| `com.xiaomi.smarthome` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.ximalaya.ting.android` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.xingin.xhs` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.xingjiabi.shengsheng` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.xiwei.logistics` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.xmcy.hykb` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.xs.fm` | Yes | Yes | Yes | nsd, cat | 1x2, 2x1, 2x2 | 18 |
| `com.xt.retouch` | Yes | Yes | Yes | nsd | 1x2, 2x1, 2x2 | 17 |
| `com.xunlei.downloadprovider` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.xunmeng.merchant` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.xunmeng.pinduoduo` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.yahoo.mobile.client.android.mail` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.ygkj.chelaile.standard` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.yiche.autoeasy` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.yipiao` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.yitong.mbank` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.yitong.mbank.psbc` | Yes | Yes | Yes | mat, nsd | 1x2, 2x1, 2x2 | 18 |
| `com.yitong.mbank.psbc.creditcard` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.yitong.zjrc.mfs.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.yixingangqin.net.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.yiyou.ga` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.youdao.dict` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.youku.phone` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `com.youloft.calendar` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `com.youxi.wnwfttl` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.yueme.itv` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.zengame.zrttddz.nearme.gamecenter` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 17 |
| `com.zhaopin.social` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `com.zhiliaoapp.musically` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.zhiliaoapp.musically.music` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.zhiliaoapp.musically.shop` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.zidongdianji` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.ziipin.softkeyboard` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.zing.mp3` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `com.zing.zalo` | Yes | Yes | Yes | outline, cat | 1x2, 2x1, 2x2 | 18 |
| `com.zmzx.college.search` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.zqer.zyweather` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `com.zuoyebang.knowledge` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `csii.com.qny` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `ctrip.android.view` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `ctrip.english` | Yes | Yes | Yes | cat | 1x2, 2x1, 2x2 | 17 |
| `drama.dachuangnanningltd.home` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `flipboard.app` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `im.weshine.keyboard` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `in.mohalla.sharechat` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `in.startv.hotstar` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `in.swiggy.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `io.dcloud.H576E6CC7` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `jp.naver.line.android` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `live.shorttv.apps` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `mark.via` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `me.ele` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `me.lyft.android` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `me.yidui` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `net.evecom.android.mztapp` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `net.huanci.hsjpro` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `net.one97.paytm` | Yes | Yes | Yes | outline | 1x2, 2x1, 2x2 | 17 |
| `net.oneplus.forums` | Yes | Yes | Yes | mat, peb, outline | 1x2, 2x1, 2x2 | 19 |
| `net.oneplus.weather` | Yes | Yes | Yes | mat, peb, day, nsd | 1x2, 2x1, 2x2 | 20 |
| `org.telegram.messenger` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `org.thunderdog.challegram` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `pi.browser` | Yes | Yes | Yes | — | 1x2, 2x1, 2x2 | 16 |
| `tv.danmaku.bili` | Yes | Yes | Yes | mat, nsd, cat | 1x2, 2x1, 2x2 | 19 |
| `tv.twitch.android.app` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
| `us.zoom.videomeetings` | Yes | Yes | Yes | mat | 1x2, 2x1, 2x2 | 17 |
