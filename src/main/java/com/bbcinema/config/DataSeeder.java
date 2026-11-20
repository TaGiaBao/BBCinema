package com.bbcinema.config;

import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.*;
import com.bbcinema.service.BookingService;
import com.bbcinema.service.PricingService;
import com.bbcinema.service.RoomService;
import com.bbcinema.service.ShowtimeService;
import com.bbcinema.service.UserService;
import com.bbcinema.util.SlugUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Fills an empty database with demo data: accounts, cinemas, rooms, movies, prices, combos,
 * promotions, news and a week of showtimes. Every night it adds showtimes for the new day, so the
 * demo always has something to book. Turn it off with app.seed-data=false.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    /** Marks the payments of sample bookings; their tickets are never e-mailed. */
    public static final String SEED_MARK = "SEED";


    private static final List<LocalTime> FIRST_SHOWS = List.of(LocalTime.of(8, 45), LocalTime.of(9, 15), LocalTime.of(9, 40), LocalTime.of(10, 5));
    private static final LocalTime LAST_START = LocalTime.of(22, 30);

    private final AppProperties properties;
    private final UserRepository userRepository;
    private final UserService userService;
    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;
    private final CinemaRepository cinemaRepository;
    private final RoomRepository roomRepository;
    private final RoomService roomService;
    private final com.bbcinema.service.ReviewService reviewService;
    private final PricingService pricingService;
    private final TicketPriceRepository ticketPriceRepository;
    private final HolidayRepository holidayRepository;
    private final ComboRepository comboRepository;
    private final PromotionRepository promotionRepository;
    private final PostRepository postRepository;
    private final BannerRepository bannerRepository;
    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeService showtimeService;
    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;
    private final com.bbcinema.service.InventoryService inventoryService;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seedData()) {
            return;
        }
        if (userRepository.count() == 0) {
            log.info("Empty database: creating demo data...");
            transactionTemplate.executeWithoutResult(status -> {
                seedAccounts();
                seedPrices();
                seedHolidays();
                seedCombos();
                seedPromotions();
                seedContent();
                seedMovies();
            });
            seedCinemas();
            transactionTemplate.executeWithoutResult(status -> seedManagersAndStock());
            seedSpecialShowtimes();
            ensureUpcomingShowtimes();
            seedSampleBookings();
            seedReviews();
            log.info("Demo data ready. General manager: admin@bbcinema.vn / Admin@123, counter manager: manager@bbcinema.vn / Manager@123");
        } else {
            ensureUpcomingShowtimes();
        }
    }

    /** Adds showtimes for the coming week every night at 00:05. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void nightly() {
        if (properties.seedData()) {
            ensureUpcomingShowtimes();
        }
    }

    // ------------------------------------------------------------------ accounts

    private void seedAccounts() {
        userService.createAccount("admin@bbcinema.vn", "Quản trị BBCinema", "Admin@123", Role.ADMIN);
        userService.createAccount("staff@bbcinema.vn", "Nhân viên quầy vé", "Staff@123", Role.STAFF);
        User member = userService.createAccount("user@bbcinema.vn", "Nguyễn Minh Anh", "User@123", Role.USER);
        member.setPhone("0912345678");
        member.setBirthDate(LocalDate.of(2003, 5, 14));
        member.setPoints(30);
        User teen = userService.createAccount("teen@bbcinema.vn", "Trần Gia Huy", "Teen@123", Role.USER);
        teen.setBirthDate(LocalDate.now(clock).minusYears(14));
        String[][] others = {
                {"lan.pham@example.com", "Phạm Thu Lan"}, {"duc.le@example.com", "Lê Văn Đức"},
                {"hoa.vu@example.com", "Vũ Thị Hoa"}, {"nam.do@example.com", "Đỗ Hoàng Nam"}};
        for (String[] o : others) {
            User u = userService.createAccount(o[0], o[1], "User@123", Role.USER);
            u.setBirthDate(LocalDate.of(1998, 3, 2));
        }
    }

    /**
     * One counter manager per cinema, the snack-bar item list, and goods-received notes: one approved
     * (so there is stock) and one waiting for the general manager.
     */
    private void seedManagersAndStock() {
        List<Cinema> cinemas = cinemaRepository.findAll().stream().sorted(java.util.Comparator.comparing(Cinema::getId)).toList();
        User manager = userService.createAccount("manager@bbcinema.vn", "Lê Quản Lý", "Manager@123", Role.MANAGER);
        manager.setCinema(cinemas.get(0));
        User staff = userRepository.findByEmailIgnoreCase("staff@bbcinema.vn").orElseThrow();
        staff.setCinema(cinemas.get(0));
        if (cinemas.size() > 1) {
            User second = userService.createAccount("manager.hd@bbcinema.vn", "Phạm Quản Lý", "Manager@123", Role.MANAGER);
            second.setCinema(cinemas.get(1));
        }
        // Snack-bar items, each counted in its own unit (corn by kg, soft drinks by can or bottle, crisps by bag).
        Object[][] items = {
                {"Ngô hạt rang bắp", "kg", "60"}, {"Bơ caramel", "kg", "15"}, {"Hộp bắp giấy", "cái", "1000"},
                {"Nước ngọt Coca-Cola", "lon", "600"}, {"Nước ngọt Pepsi", "chai", "300"}, {"Bim bim khoai tây", "gói", "200"}};
        Map<String, Long> ids = new HashMap<>();
        Map<Long, BigDecimal> delivery = new HashMap<>();
        Map<Long, BigDecimal> topUp = new HashMap<>();
        for (Object[] item : items) {
            Long id = inventoryService.addItem((String) item[0], (String) item[1]).getId();
            ids.put((String) item[0], id);
            delivery.put(id, new BigDecimal((String) item[2]));
            topUp.put(id, new BigDecimal((String) item[2]).divide(BigDecimal.valueOf(2)));
        }
        // What one portion of each combo takes from the stock.
        Map<String, String[][]> recipes = Map.of(
                "Bắp ngọt nhỏ", new String[][]{{"Ngô hạt rang bắp", "0.1"}, {"Bơ caramel", "0.02"}, {"Hộp bắp giấy", "1"}},
                "Combo Solo", new String[][]{{"Ngô hạt rang bắp", "0.15"}, {"Bơ caramel", "0.03"}, {"Hộp bắp giấy", "1"},
                        {"Nước ngọt Coca-Cola", "1"}},
                "Combo Couple", new String[][]{{"Ngô hạt rang bắp", "0.2"}, {"Bơ caramel", "0.04"}, {"Hộp bắp giấy", "1"},
                        {"Nước ngọt Coca-Cola", "1"}, {"Nước ngọt Pepsi", "1"}},
                "Combo Gia đình", new String[][]{{"Ngô hạt rang bắp", "0.4"}, {"Bơ caramel", "0.08"}, {"Hộp bắp giấy", "2"},
                        {"Nước ngọt Coca-Cola", "2"}, {"Nước ngọt Pepsi", "2"}, {"Bim bim khoai tây", "1"}});
        for (Combo combo : comboRepository.findAll()) {
            String[][] recipe = recipes.get(combo.getName());
            if (recipe != null) {
                Map<Long, BigDecimal> uses = new HashMap<>();
                for (String[] use : recipe) {
                    uses.put(ids.get(use[0]), new BigDecimal(use[1]));
                }
                inventoryService.saveRecipe(combo.getId(), uses);
            }
        }
        // Each cinema received a first delivery (approved); Cau Giay also has a top-up waiting for approval.
        User admin = userRepository.findByEmailIgnoreCase("admin@bbcinema.vn").orElseThrow();
        for (User m : userRepository.findAll().stream().filter(u -> u.getRole() == Role.MANAGER).toList()) {
            var approved = inventoryService.request(m.getId(), delivery, "Nhập đầu tháng, NCC Phương Nam");
            inventoryService.review(approved.getId(), admin.getId(), true, "Đã kiểm hàng");
        }
        inventoryService.request(manager.getId(), topUp, "Bổ sung cuối tuần");
    }

    // ------------------------------------------------------------------ prices, combos, promotions

    private void seedPrices() {
        // {standard, vip} for 2D; 3D adds 20,000. Couple seats cost two VIP seats (PricingService).
        Map<String, int[]> base = Map.of(
                "WEEKDAY_BEFORE_17", new int[]{60_000, 70_000},
                "WEEKDAY_FROM_17", new int[]{75_000, 85_000},
                "WEEKEND_BEFORE_17", new int[]{80_000, 90_000},
                "WEEKEND_FROM_17", new int[]{90_000, 100_000});
        for (ScreenFormat format : ScreenFormat.values()) {
            for (DayType day : List.of(DayType.WEEKDAY, DayType.WEEKEND)) {
                for (TimeSlot slot : TimeSlot.values()) {
                    int[] values = base.get(day.name() + "_" + slot.name());
                    SeatType[] types = {SeatType.STANDARD, SeatType.VIP};
                    for (int i = 0; i < types.length; i++) {
                        int extra = format == ScreenFormat.THREE_D ? 20_000 : 0;
                        BigDecimal price = BigDecimal.valueOf(values[i] + extra);
                        ticketPriceRepository.save(new TicketPrice(types[i], format, day, slot, price));
                        if (day == DayType.WEEKEND) {
                            ticketPriceRepository.save(new TicketPrice(types[i], format, DayType.HOLIDAY, slot, holidayPriceFrom(price)));
                        }
                    }
                }
            }
        }
        pricingService.ensureAllPricesExist(BigDecimal.valueOf(80_000));
    }

    /** Demo holiday price: the weekend price plus 10%, rounded to 1,000đ. */
    static BigDecimal holidayPriceFrom(BigDecimal weekendPrice) {
        return weekendPrice.multiply(BigDecimal.valueOf(11)).divide(BigDecimal.valueOf(10))
                .divide(BigDecimal.valueOf(1000)).setScale(0, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(1000));
    }

    /**
     * Public holidays fixed on the solar calendar, each at its next date from today (so a year ahead).
     * Lunar holidays (Tết, Giỗ Tổ Hùng Vương) and the extra day next to 2/9 change every year; the
     * admin adds them on the holidays page.
     */
    private void seedHolidays() {
        LocalDate today = LocalDate.now(clock);
        for (Map.Entry<MonthDay, String> fixed : FIXED_HOLIDAYS.entrySet()) {
            LocalDate date = fixed.getKey().atYear(today.getYear());
            if (date.isBefore(today)) {
                date = fixed.getKey().atYear(today.getYear() + 1);
            }
            holidayRepository.save(new Holiday(date, fixed.getValue()));
        }
    }

    static final Map<MonthDay, String> FIXED_HOLIDAYS = new LinkedHashMap<>();

    static {
        FIXED_HOLIDAYS.put(MonthDay.of(1, 1), "Tết Dương lịch");
        FIXED_HOLIDAYS.put(MonthDay.of(4, 30), "Ngày Giải phóng miền Nam 30/4");
        FIXED_HOLIDAYS.put(MonthDay.of(5, 1), "Ngày Quốc tế Lao động 1/5");
        FIXED_HOLIDAYS.put(MonthDay.of(9, 2), "Quốc khánh 2/9");
    }

    private void seedCombos() {
        Object[][] combos = {
                {"Bắp ngọt nhỏ", "1 bắp ngọt size S", 45_000, "/images/combos/popcorn.svg"},
                {"Combo Solo", "1 bắp ngọt size M + 1 nước ngọt size M", 79_000, "/images/combos/solo.svg"},
                {"Combo Couple", "1 bắp ngọt size L + 2 nước ngọt size M", 109_000, "/images/combos/couple.svg"},
                {"Combo Gia đình", "2 bắp ngọt size L + 4 nước ngọt size M + 1 snack", 189_000, "/images/combos/family.svg"}};
        for (Object[] c : combos) {
            Combo combo = new Combo();
            combo.setName((String) c[0]);
            combo.setDescription((String) c[1]);
            combo.setPrice(BigDecimal.valueOf((int) c[2]));
            combo.setImagePath((String) c[3]);
            comboRepository.save(combo);
        }
    }

    private void seedPromotions() {
        LocalDate today = LocalDate.now(clock);
        promotionRepository.save(promotion("BBCINEMA10", "Giảm 10% cho mọi đơn, tối đa 30.000đ", 10, 30_000, null, today.minusDays(7), today.plusMonths(3), null));
        promotionRepository.save(promotion("THANHVIEN20", "Giảm 20% cho đơn từ 200.000đ, tối đa 60.000đ", 20, 60_000, 200_000, today.minusDays(1), today.plusMonths(1), 100));
        promotionRepository.save(promotion("HETHAN", "Mã đã hết hạn, dùng để thử thông báo lỗi", 50, null, null, today.minusMonths(2), today.minusMonths(1), null));
    }

    private static Promotion promotion(String code, String description, int percent, Integer max, Integer min,
                                       LocalDate from, LocalDate to, Integer limit) {
        Promotion p = new Promotion();
        p.setCode(code);
        p.setDescription(description);
        p.setDiscountPercent(percent);
        p.setMaxDiscount(max == null ? null : BigDecimal.valueOf(max));
        p.setMinOrder(min == null ? null : BigDecimal.valueOf(min));
        p.setValidFrom(from);
        p.setValidTo(to);
        p.setUsageLimit(limit);
        return p;
    }

    // ------------------------------------------------------------------ news and banners

    private void seedContent() {
        LocalDateTime now = LocalDateTime.now(clock);
        Object[][] posts = {
                {"Thứ Tư vui vẻ: đồng giá 50.000đ", PostType.PROMOTION, "/images/posts/wednesday.svg",
                        "Mỗi thứ Tư, mọi suất chiếu 2D trước 17h chỉ còn 50.000đ cho ghế thường.",
                        "Áp dụng tại tất cả rạp BBCinema vào thứ Tư hằng tuần.\n\nKhông áp dụng cùng các chương trình khuyến mãi khác. Không áp dụng cho suất chiếu đặc biệt và ngày lễ."},
                {"Tích điểm thành viên: 5% giá trị mỗi đơn", PostType.PROMOTION, "/images/posts/points.svg",
                        "Mỗi đơn hàng thanh toán thành công được tích 5% giá trị, dùng để giảm giá ở lần sau.",
                        "1 điểm tương đương 1.000đ khi thanh toán.\n\nĐiểm được cộng ngay sau khi thanh toán và bị thu hồi nếu bạn huỷ vé. Mỗi đơn được dùng điểm để giảm tối đa 50% giá trị."},
                {"BBCinema Hà Đông khai trương phòng chiếu 3D", PostType.NEWS, "/images/posts/opening.svg",
                        "Phòng chiếu 3D mới với 140 ghế, âm thanh vòm và ghế đôi ở hàng cuối.",
                        "Phòng 3 tại BBCinema Hà Đông chính thức hoạt động từ tuần này.\n\nPhòng có 10 hàng ghế: hàng A-E ghế thường, hàng F-I ghế VIP và hàng J ghế đôi ở cuối phòng."},
                {"Lịch chiếu sớm \"Hành Tinh Lặng Im\"", PostType.NEWS, "/images/posts/premiere.svg",
                        "Suất chiếu đặc biệt trước ngày khởi chiếu chính thức, số lượng ghế có hạn.",
                        "Khán giả có thể đặt vé các suất chiếu sớm ngay hôm nay trong tab \"Suất chiếu đặc biệt\" ở trang chủ.\n\nVé suất chiếu sớm không áp dụng mã giảm giá thành viên."}};
        int i = 0;
        for (Object[] p : posts) {
            Post post = new Post();
            post.setTitle((String) p[0]);
            post.setSlug(SlugUtil.toSlug((String) p[0]));
            post.setType((PostType) p[1]);
            post.setThumbnailPath((String) p[2]);
            post.setSummary((String) p[3]);
            post.setContent((String) p[4]);
            post.setPublishedAt(now.minusDays(i++ * 3L));
            postRepository.save(post);
        }
        // title, subtitle, English title, English subtitle, picture, link
        String[][] banners = {
                {"Đặt vé nhanh, chọn ghế đẹp", "Giữ ghế 5 phút, thanh toán trong một bước",
                        "Book fast, pick great seats", "Seats held for 5 minutes, pay in one step",
                        "/images/banners/banner-1.svg", "/schedule"},
                {"Thứ Tư vui vẻ", "Đồng giá 50.000đ cho suất 2D trước 17h",
                        "Happy Wednesday", "All 2D shows before 5 pm at 50,000đ",
                        "/images/banners/banner-2.svg", "/news"},
                {"Hành Tinh Lặng Im", "Suất chiếu sớm 23h, chỉ trong 2 đêm",
                        "Hành Tinh Lặng Im", "Early screenings at 11 pm, two nights only",
                        "/images/banners/banner-3.svg", "/movies/hanh-tinh-lang-im"}};
        int order = 1;
        for (String[] b : banners) {
            Banner banner = new Banner();
            banner.setTitle(b[0]);
            banner.setSubtitle(b[1]);
            banner.setTitleEn(b[2]);
            banner.setSubtitleEn(b[3]);
            banner.setImagePath(b[4]);
            banner.setLink(b[5]);
            banner.setSortOrder(order++);
            bannerRepository.save(banner);
        }
    }

    // ------------------------------------------------------------------ movies

    private void seedMovies() {
        Map<String, Genre> genres = new HashMap<>();
        for (String name : List.of("Hành động", "Khoa học viễn tưởng", "Tình cảm", "Gia đình", "Kinh dị", "Hoạt hình",
                "Hài", "Phiêu lưu", "Hình sự", "Hồi hộp", "Âm nhạc", "Tâm lý", "Học đường")) {
            genres.put(name, genreRepository.save(new Genre(name)));
        }
        LocalDate today = LocalDate.now(clock);
        Object[][] movies = {
                {"Vệt Sáng Cuối Cùng", "Hoàng Quốc Bảo", "Lý Minh Khang, Đào Ngọc Hân", 128, AgeRating.T13, -12, MovieStatus.NOW_SHOWING, "vet-sang", "Hành động,Khoa học viễn tưởng",
                        "Năm 2087, một phi công vận tải phát hiện tín hiệu lạ phát ra từ trạm năng lượng bỏ hoang ngoài quỹ đạo Sao Hỏa. Cô có 48 giờ để tìm ra nguồn gốc của nó trước khi trạm rơi xuống thành phố đông dân nhất hành tinh."},
                {"Mùa Hè Ở Phố Cổ", "Nguyễn Thanh Vân", "Phạm Gia Linh, Trịnh Hữu Phước", 105, AgeRating.P, -20, MovieStatus.NOW_SHOWING, "mua-he", "Tình cảm,Gia đình",
                        "Một cô gái Sài Gòn về Hội An giúp bà ngoại giữ lại tiệm đèn lồng ba đời. Mùa hè ấy, cô học cách làm đèn, kết bạn với cậu thợ mộc bên kia sông và hiểu thêm về người mẹ đã mất."},
                {"Căn Nhà Số 13", "Trần Vĩnh Lộc", "Võ Thảo My, Lâm Tuấn", 98, AgeRating.T18, -5, MovieStatus.NOW_SHOWING, "can-nha", "Kinh dị",
                        "Bốn người bạn thuê một căn biệt thự giá rẻ ở Đà Lạt cho kỳ nghỉ cuối tuần. Đêm đầu tiên, họ phát hiện căn phòng thứ 13 không có trong bản vẽ ngôi nhà."},
                {"Biệt Đội Mèo Hoang", "Lê Hải Đăng", "Lồng tiếng: Mai Anh, Quốc Trung", 92, AgeRating.P, -9, MovieStatus.NOW_SHOWING, "meo-hoang", "Hoạt hình,Hài",
                        "Năm chú mèo hoang ở chợ Bến Thành lập thành biệt đội để giải cứu chú chó già bị bắt nhầm. Hành trình xuyên thành phố đầy tiếng cười và những màn rượt đuổi trên mái nhà."},
                {"Đỉnh Gió Bắc", "Phan Đức Trí", "Hà Minh Quân, Nông Thị Lam", 134, AgeRating.T13, -3, MovieStatus.NOW_SHOWING, "dinh-gio", "Phiêu lưu,Hành động",
                        "Một đội cứu hộ vùng cao băng qua Fansipan giữa trận bão tuyết hiếm gặp để tìm nhóm sinh viên mất tích. Thời tiết, độ cao và bí mật của người dẫn đường khiến chuyến đi ngày càng nguy hiểm."},
                {"Kẻ Gác Đêm", "Đinh Công Thành", "Bùi Anh Tú, Kiều Phương Thảo", 116, AgeRating.T16, -15, MovieStatus.NOW_SHOWING, "gac-dem", "Hình sự,Hồi hộp",
                        "Người bảo vệ ca đêm của một tòa nhà văn phòng trở thành nhân chứng duy nhất của vụ trộm dữ liệu ngân hàng. Anh phải tự chứng minh mình vô tội trước khi trời sáng."},
                {"Tiếng Đàn Trên Sông", "Võ Hoài Nam", "Châu Bảo Ngọc, Tạ Quang Vinh", 110, AgeRating.K, -25, MovieStatus.NOW_SHOWING, "tieng-dan", "Âm nhạc,Tâm lý",
                        "Một nghệ sĩ đàn bầu trẻ ở miền Tây phải chọn giữa hợp đồng thu âm ở thành phố và lời hứa giữ lại đoàn nhạc của cha trên chiếc ghe cũ."},
                {"Hành Tinh Lặng Im", "Hoàng Quốc Bảo", "Đào Ngọc Hân, Phùng Khải", 141, AgeRating.T13, 10, MovieStatus.COMING_SOON, "hanh-tinh", "Khoa học viễn tưởng,Phiêu lưu",
                        "Phần tiếp theo của Vệt Sáng Cuối Cùng. Tín hiệu lạ dẫn phi hành đoàn đến một hành tinh nơi mọi âm thanh đều biến mất, và thứ gì đó đang theo dõi họ trong im lặng."},
                {"Lớp Học Siêu Quậy", "Mạc Văn Tâm", "Trương Hải Yến, Vũ Đức Anh", 99, AgeRating.T13, 17, MovieStatus.COMING_SOON, "lop-hoc", "Hài,Học đường",
                        "Lớp 11A7 nổi tiếng nhất trường vì quậy phá. Cô giáo chủ nhiệm mới đặt cược: nếu cả lớp đạt giải văn nghệ, cô sẽ để họ tự tổ chức chuyến dã ngoại cuối năm."},
                {"Ngôi Đền Cát", "Phan Đức Trí", "Hà Minh Quân, Lục Ánh Dương", 125, AgeRating.T16, 24, MovieStatus.COMING_SOON, "ngoi-den", "Phiêu lưu,Hành động",
                        "Nhà khảo cổ trẻ theo dấu tấm bản đồ Chăm Pa cổ vào vùng đồi cát Ninh Thuận, nơi một nhóm săn cổ vật cũng đang tìm cùng một ngôi đền."}};
        for (Object[] m : movies) {
            Movie movie = new Movie();
            movie.setTitle((String) m[0]);
            movie.setSlug(SlugUtil.toSlug((String) m[0]));
            movie.setDirector((String) m[1]);
            movie.setActors((String) m[2]);
            movie.setDurationMinutes((int) m[3]);
            movie.setAgeRating((AgeRating) m[4]);
            movie.setReleaseDate(today.plusDays((int) m[5]));
            movie.setStatus((MovieStatus) m[6]);
            movie.setPosterPath("/images/posters/" + m[7] + ".svg");
            movie.setLanguage("Tiếng Việt");
            movie.setDescription((String) m[9]);
            for (String g : ((String) m[8]).split(",")) {
                movie.getGenres().add(genres.get(g));
            }
            movieRepository.save(movie);
        }
    }

    // ------------------------------------------------------------------ cinemas and rooms

    private void seedCinemas() {
        // Approximate map positions of the two addresses, for the "nearest cinema" suggestion.
        Cinema cauGiay = cinema("BBCinema Cầu Giấy", "Tầng 5, 241 Xuân Thủy, Cầu Giấy, Hà Nội", "0243 888 1111", 21.0368, 105.7820);
        Cinema haDong = cinema("BBCinema Hà Đông", "Tầng 4, 10 Quang Trung, Hà Đông, Hà Nội", "0243 888 2222", 20.9714, 105.7788);
        // Default layout: A-E standard, F-I VIP, couple seats in the last row (J).
        room(cauGiay, "Phòng 1", 10, 12, "F,G,H,I", "J");
        room(cauGiay, "Phòng 2", 10, 12, "F,G,H,I", "J");
        room(cauGiay, "Phòng 3", 9, 10, "F,G,H,I", "");
        room(haDong, "Phòng 1", 10, 12, "F,G,H,I", "J");
        room(haDong, "Phòng 2", 10, 12, "F,G,H,I", "J");
        Room threeD = room(haDong, "Phòng 3 (3D)", 10, 14, "F,G,H,I", "J");
        // A Lotte-style room made with the layout editor's data: two aisles split the room into
        // side blocks of 2 seats and a middle block, and a walkway separates standard from VIP.
        addAisles(threeD, new int[]{2, 12}, 5);
    }

    /** Moves seats right of each aisle column one cell further, and seats from walkwayLine down. */
    private void addAisles(Room room, int[] aisleColumns, int walkwayLine) {
        for (Seat seat : room.getSeats()) {
            int x = seat.getGridX();
            int shift = 0;
            for (int column : aisleColumns) {
                if (x >= column) {
                    shift++;
                }
            }
            seat.setGridX(x + shift);
            if (seat.getGridY() >= walkwayLine) {
                seat.setGridY(seat.getGridY() + 1);
            }
        }
        room.setSeatsPerRow(room.getSeatsPerRow() + aisleColumns.length);
        room.setRowCount(room.getRowCount() + 1);
        roomRepository.save(room);
    }

    private Cinema cinema(String name, String address, String phone, double latitude, double longitude) {
        Cinema c = new Cinema();
        c.setName(name);
        c.setAddress(address);
        c.setPhone(phone);
        c.setLatitude(latitude);
        c.setLongitude(longitude);
        return cinemaRepository.save(c);
    }

    private Room room(Cinema cinema, String name, int rows, int perRow, String vip, String couple) {
        RoomForm form = new RoomForm();
        form.setCinemaId(cinema.getId());
        form.setName(name);
        form.setRowCount(rows);
        form.setSeatsPerRow(perRow);
        form.setVipRows(vip);
        form.setCoupleRows(couple);
        return roomService.create(form);
    }

    // ------------------------------------------------------------------ showtimes

    /** Early screenings of the upcoming sequel, shown in the "special" tab. */
    private void seedSpecialShowtimes() {
        Movie premiere = movieRepository.findBySlug("hanh-tinh-lang-im").orElseThrow();
        LocalDate today = LocalDate.now(clock);
        for (Room room : roomRepository.findAllWithCinema()) {
            if (room.getName().startsWith("Phòng 1")) {
                for (int d = 2; d <= 3; d++) {
                    tryCreate(premiere, room, today.plusDays(d).atTime(23, 0), ScreenFormat.TWO_D, true);
                }
            }
        }
    }

    /**
     * Makes sure every active room has showtimes for the whole booking window (today and the next 13 days). Movies now
     * showing rotate through the rooms from morning until the last show starting before 22:30.
     */
    public void ensureUpcomingShowtimes() {
        List<Movie> showing = movieRepository.findByStatusOrderByReleaseDateDesc(MovieStatus.NOW_SHOWING);
        if (showing.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Room> rooms = roomRepository.findAllWithCinema().stream().filter(Room::isActive).toList();
        int created = 0;
        for (int d = 0; d < ShowtimeService.BOOKING_WINDOW_DAYS; d++) {
            LocalDate date = now.toLocalDate().plusDays(d);
            for (int r = 0; r < rooms.size(); r++) {
                Room room = rooms.get(r);
                boolean hasRegular = showtimeRepository.findOverlapping(room.getId(), date.atStartOfDay(), date.atTime(LAST_START), null)
                        .stream().anyMatch(s -> !s.isSpecial());
                if (hasRegular) {
                    continue;
                }
                ScreenFormat format = room.getName().contains("3D") ? ScreenFormat.THREE_D : ScreenFormat.TWO_D;
                LocalDateTime start = date.atTime(FIRST_SHOWS.get(r % FIRST_SHOWS.size()));
                int index = r + d;
                while (!start.toLocalTime().isAfter(LAST_START) && start.toLocalDate().equals(date)) {
                    Movie movie = showing.get(index++ % showing.size());
                    if (start.isAfter(now.plusMinutes(10)) && tryCreate(movie, room, start, format, false)) {
                        created++;
                    }
                    start = roundUpToQuarter(start.plusMinutes(movie.getDurationMinutes() + 20L));
                }
            }
        }
        if (created > 0) {
            log.info("Created {} demo showtimes for the coming week", created);
        }
    }

    private boolean tryCreate(Movie movie, Room room, LocalDateTime start, ScreenFormat format, boolean special) {
        try {
            showtimeService.create(movie, room, start, format, special);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private static LocalDateTime roundUpToQuarter(LocalDateTime time) {
        int minute = time.getMinute();
        int rounded = ((minute + 14) / 15) * 15;
        return time.withMinute(0).withSecond(0).withNano(0).plusMinutes(rounded);
    }

    // ------------------------------------------------------------------ sample bookings for the dashboard

    /** Paid orders spread over the last two weeks so the revenue chart is not empty. */
    private void seedSampleBookings() {
        List<User> members = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.USER && u.getBirthDate() != null && u.ageOn(LocalDate.now(clock)) >= 18)
                .toList();
        List<Long> showtimeIds = transactionTemplate.execute(status -> showtimeRepository.findAll().stream()
                .filter(s -> !s.isSpecial() && !s.isCancelled())
                .sorted(Comparator.comparing(Showtime::getStartTime))
                .limit(40)
                .map(Showtime::getId)
                .toList());
        if (members.isEmpty() || showtimeIds == null || showtimeIds.isEmpty()) {
            return;
        }
        Random random = new Random(26);
        int made = 0;
        for (int i = 0; i < 28; i++) {
            Long showtimeId = showtimeIds.get(random.nextInt(showtimeIds.size()));
            User member = members.get(random.nextInt(members.size()));
            int daysAgo = random.nextInt(14);
            int seats = 1 + random.nextInt(3);
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    List<Long> free = showtimeService.seatMap(showtimeId).rows().stream()
                            .flatMap(row -> row.cells().stream())
                            .filter(c -> !c.isGap() && c.isAvailable())
                            .map(com.bbcinema.dto.response.SeatCell::showtimeSeatId)
                            .limit(seats)
                            .toList();
                    if (free.isEmpty()) {
                        return;
                    }
                    Booking booking = bookingService.holdSeats(member.getId(), showtimeId, free);
                    bookingService.confirmPayment(booking, PaymentMethod.BANK_QR, BookingService.bankTransferRef(booking.getCode()), SEED_MARK);
                    LocalDateTime paidAt = LocalDateTime.now(clock).minusDays(daysAgo).withHour(9 + random.nextInt(12));
                    booking.setCreatedAt(paidAt.minusMinutes(3));
                    booking.setPaidAt(paidAt);
                });
                made++;
            } catch (RuntimeException e) {
                log.debug("Skipped a sample booking: {}", e.getMessage());
            }
        }
        log.info("Created {} sample paid bookings", made);
    }

    /** A few member reviews so the movie cards show ratings. */
    private void seedReviews() {
        String[][] samples = {
                {"user@bbcinema.vn", "vet-sang", "5", "Kỹ xảo đẹp, nhạc phim rất cuốn. Đáng xem ở phòng 3D."},
                {"lan.pham@example.com", "vet-sang", "4", "Nửa đầu hơi chậm nhưng cái kết rất đã."},
                {"duc.le@example.com", "mua-he", "5", "Nhẹ nhàng, dễ thương, xem xong muốn về Hội An ngay."},
                {"user@bbcinema.vn", "meo-hoang", "4", "Bé nhà mình cười suốt cả phim."},
                {"hoa.vu@example.com", "can-nha", "3", "Hù doạ ổn nhưng kịch bản đoán được."},
                {"nam.do@example.com", "dinh-gio", "5", "Cảnh quay núi tuyết quá đỉnh."},
                {"lan.pham@example.com", "gac-dem", "4", "Căng thẳng tới phút cuối."}};
        for (String[] r : samples) {
            User user = userRepository.findByEmailIgnoreCase(r[0]).orElse(null);
            Movie movie = movieRepository.findAll().stream()
                    .filter(m -> m.getPosterPath() != null && m.getPosterPath().contains("/" + r[1] + ".")).findFirst().orElse(null);
            if (user != null && movie != null) {
                reviewService.seed(user, movie, Integer.parseInt(r[2]), r[3]);
            }
        }
    }
}
