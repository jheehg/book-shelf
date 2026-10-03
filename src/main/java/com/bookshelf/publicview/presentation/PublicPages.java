package com.bookshelf.publicview.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

/** 공개 페이지 공통. 404는 이유와 상관없이 한 모양이다 — 다르면 그 차이가 존재를 누설한다 */
final class PublicPages {

    private PublicPages() {
    }

    static ModelAndView notFound() {
        return new ModelAndView("public/not-found", HttpStatus.NOT_FOUND);
    }

    static ModelAndView page(String view, String title, String description, String image, String url) {
        ModelAndView mav = new ModelAndView(view);
        mav.addObject("pageTitle", title);
        mav.addObject("pageDescription", description);
        mav.addObject("pageImage", image);
        mav.addObject("pageUrl", url);
        return mav;
    }
}
